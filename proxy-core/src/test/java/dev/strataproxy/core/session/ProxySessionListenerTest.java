package dev.strataproxy.core.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.strataproxy.api.PlacementDecision;
import dev.strataproxy.core.auth.MinecraftEncryptionRequest;
import dev.strataproxy.core.auth.MinecraftEncryptionResponse;
import dev.strataproxy.core.auth.ProfileProperty;
import dev.strataproxy.core.auth.VerifiedProfile;
import dev.strataproxy.core.backend.BackendId;
import dev.strataproxy.core.backend.BackendCatalog;
import dev.strataproxy.core.backend.BackendHandle;
import dev.strataproxy.core.backend.BackendOwner;
import dev.strataproxy.core.backend.BackendRegistration;
import dev.strataproxy.core.backend.BackendView;
import dev.strataproxy.core.backend.InMemoryBackendCatalog;
import org.junit.jupiter.api.Test;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProxySessionListenerTest {
    @Test
    void shutdownWhileAcceptingClientsClosesEveryRegisteredSession() throws Exception {
        var listener = new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                new InMemoryBackendCatalog());
        listener.setPlacement(player -> CompletableFuture.completedFuture(Optional.empty()));
        var clients = Collections.synchronizedList(new ArrayList<Socket>());
        var firstConnected = new CountDownLatch(1);
        CompletableFuture<Void> connector = null;
        try {
            int port = ((InetSocketAddress) listener.start().toCompletableFuture()
                    .get(5, TimeUnit.SECONDS).localAddress()).getPort();
            connector = CompletableFuture.runAsync(() -> {
                for (int i = 0; i < 64; i++) {
                    try {
                        Socket client = new Socket(InetAddress.getLoopbackAddress(), port);
                        clients.add(client);
                        firstConnected.countDown();
                    } catch (IOException listenerClosed) {
                        return;
                    }
                }
            });
            assertTrue(firstConnected.await(5, TimeUnit.SECONDS));
            listener.close().toCompletableFuture().get(15, TimeUnit.SECONDS);
            connector.get(5, TimeUnit.SECONDS);
            assertTrue(listener.allSessions().isEmpty(), "shutdown left an accepted session registered");
            for (Socket client : clients) {
                client.setSoTimeout(2000);
                try {
                    assertEquals(-1, client.getInputStream().read());
                } catch (SocketException reset) {
                    // A peer close with unread login bytes can surface as a TCP reset on Windows.
                }
            }
        } finally {
            listener.close().toCompletableFuture().get(15, TimeUnit.SECONDS);
            if (connector != null) connector.get(5, TimeUnit.SECONDS);
            for (Socket client : clients) client.close();
        }
    }

    @Test
    void rejectsOversizedFrontendFrameBeforeAuthentication() throws Exception {
        var listener = new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                new InMemoryBackendCatalog());
        listener.setPlacement(player -> CompletableFuture.completedFuture(Optional.empty()));
        try {
            int port = ((InetSocketAddress) listener.start().toCompletableFuture()
                    .get(5, TimeUnit.SECONDS).localAddress()).getPort();
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                client.setSoTimeout(2000);
                var prefix = new ByteArrayOutputStream();
                writeVarInt(prefix, 4097);
                client.getOutputStream().write(prefix.toByteArray());
                client.getOutputStream().flush();
                assertEquals(-1, client.getInputStream().read());
            }
        } finally {
            listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void offlineBackendDoesNotReceiveClientSuppliedIdentitySegments() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        try (ServerSocket backendServer = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            var forwardedHost = new CompletableFuture<String>();
            Thread backendThread = new Thread(() -> {
                try (Socket socket = backendServer.accept()) {
                    socket.setSoTimeout(5000);
                    var handshake = new java.io.ByteArrayInputStream(
                            readFrame(new DataInputStream(socket.getInputStream())));
                    assertEquals(0, readVarInt(handshake));
                    assertEquals(5, readVarInt(handshake));
                    forwardedHost.complete(readString(handshake, 255));
                } catch (Throwable failure) { forwardedHost.completeExceptionally(failure); }
            }, "fake-host-check-backend");
            backendThread.setDaemon(true);
            backendThread.start();
            catalog.register(new BackendRegistration(new BackendId("lobby"), new BackendOwner("static", 0),
                    URI.create("tcp://127.0.0.1:" + backendServer.getLocalPort()), Map.of(), Map.of()));
            var listener = new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), catalog);
            listener.setPlacement(player -> CompletableFuture.completedFuture(
                    Optional.of(PlacementDecision.select("lobby"))));
            try {
                int port = ((InetSocketAddress) listener.start().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).localAddress()).getPort();
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                    sendLogin(client, "HostCheck", "play.example\0spoofed-ip\0spoofed-uuid\0[]");
                    assertEquals("play.example", forwardedHost.get(5, TimeUnit.SECONDS));
                }
            } finally {
                listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void initialLoginUsesCurrentAddressWhenDirectoryUpdatesBeforeSelection() throws Exception {
        String username = "MovedPlayer";
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
        var catalog = new InMemoryBackendCatalog();
        try (ServerSocket oldServer = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
             ServerSocket newServer = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            newServer.setSoTimeout(5000);
            var owner = new BackendOwner("static", 0);
            var id = new BackendId("lobby");
            var original = catalog.register(new BackendRegistration(id, owner,
                    URI.create("tcp://127.0.0.1:" + oldServer.getLocalPort())));
            var moved = new BackendRegistration(id, owner,
                    URI.create("tcp://127.0.0.1:" + newServer.getLocalPort()));
            var updated = new AtomicBoolean();
            BackendCatalog movingCatalog = new BackendCatalog() {
                @Override public BackendView register(BackendRegistration definition) {
                    return catalog.register(definition);
                }
                @Override public Optional<BackendView> update(BackendHandle handle, BackendRegistration definition) {
                    return catalog.update(handle, definition);
                }
                @Override public boolean remove(BackendHandle handle) { return catalog.remove(handle); }
                @Override public int removeOwner(BackendOwner value) { return catalog.removeOwner(value); }
                @Override public Optional<BackendView> find(BackendId value) { return catalog.find(value); }
                @Override public List<BackendView> snapshot() {
                    if (updated.compareAndSet(false, true)) catalog.update(original.handle(), moved).orElseThrow();
                    return catalog.snapshot();
                }
            };
            var listener = new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                    movingCatalog);
            listener.setPlacement(player -> CompletableFuture.completedFuture(
                    Optional.of(PlacementDecision.select("lobby"))));
            try {
                int port = ((InetSocketAddress) listener.start().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).localAddress()).getPort();
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                    client.setSoTimeout(5000);
                    sendLogin(client, username);
                    try (Socket backend = newServer.accept()) {
                        backend.setSoTimeout(5000);
                        var input = new DataInputStream(backend.getInputStream());
                        assertEquals(0, readVarInt(readFrame(input)));
                        assertEquals(0, readVarInt(readFrame(input)));
                        var success = new ByteArrayOutputStream();
                        writeVarInt(success, 2);
                        writeString(success, uuid.toString());
                        writeString(success, username);
                        writeFrame(new DataOutputStream(backend.getOutputStream()), success.toByteArray());
                        assertEquals(2, readVarInt(readFrame(new DataInputStream(client.getInputStream()))));
                        assertEquals(moved.address(), catalog.find(original.handle().id()).orElseThrow().address());
                    }
                }
            } finally {
                listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
            assertTrue(updated.get());
        }
    }

    @Test
    void closingListenerEndsPendingPlacementAndIgnoresLateDecision() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        var registered = catalog.register(new BackendRegistration(new BackendId("lobby"),
                new BackendOwner("static", 0), URI.create("tcp://127.0.0.1:1"), Map.of(), Map.of()));
        var placementEntered = new CompletableFuture<Void>();
        var decision = new CompletableFuture<Optional<PlacementDecision>>();
        var listener = new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), catalog);
        listener.setPlacement(player -> {
            placementEntered.complete(null);
            return decision;
        });
        try {
            int port = ((InetSocketAddress) listener.start().toCompletableFuture()
                    .get(5, TimeUnit.SECONDS).localAddress()).getPort();
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                client.setSoTimeout(5000);
                sendLogin(client, "PendingPlacement");
                placementEntered.get(5, TimeUnit.SECONDS);

                listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(-1, client.getInputStream().read());
                decision.complete(Optional.of(PlacementDecision.select("lobby")));
                assertTrue(listener.online().isEmpty());
                assertTrue(listener.allSessions().isEmpty());
                assertEquals(0, listener.onlineCount());
            }
        } finally {
            listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void clientDisconnectDuringPendingPlacementReleasesSessionPromptly() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        var placementEntered = new CompletableFuture<Void>();
        var decision = new CompletableFuture<Optional<PlacementDecision>>();
        var listener = new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), catalog);
        listener.setPlacement(player -> {
            placementEntered.complete(null);
            return decision;
        });
        try {
            int port = ((InetSocketAddress) listener.start().toCompletableFuture()
                    .get(5, TimeUnit.SECONDS).localAddress()).getPort();
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                sendLogin(client, "LeavingPlayer");
                placementEntered.get(5, TimeUnit.SECONDS);
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!listener.allSessions().isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue(listener.allSessions().isEmpty(), "disconnected player should not occupy a pending session");
            assertTrue(decision.isCancelled(), "disconnected player should cancel its placement request");
            decision.complete(Optional.of(PlacementDecision.reject("too late")));
            assertEquals(0, listener.onlineCount());
        } finally {
            listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void closingListenerDisconnectsActiveSession() throws Exception {
        String username = "ShutdownPlayer";
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
        var catalog = new InMemoryBackendCatalog();
        try (ServerSocket backendServer = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            var backendClosed = new CompletableFuture<Void>();
            Thread backendThread = new Thread(() -> {
                try (Socket socket = backendServer.accept()) {
                    socket.setSoTimeout(5000);
                    var input = new DataInputStream(socket.getInputStream());
                    var output = new DataOutputStream(socket.getOutputStream());
                    readFrame(input);
                    readFrame(input);
                    ByteArrayOutputStream success = new ByteArrayOutputStream();
                    writeVarInt(success, 2);
                    writeString(success, uuid.toString());
                    writeString(success, username);
                    writeFrame(output, success.toByteArray());
                    assertEquals(-1, input.read());
                    backendClosed.complete(null);
                } catch (Throwable failure) { backendClosed.completeExceptionally(failure); }
            }, "fake-shutdown-backend");
            backendThread.setDaemon(true);
            backendThread.start();

            var registered = catalog.register(new BackendRegistration(new BackendId("lobby"),
                    new BackendOwner("static", 0),
                    URI.create("tcp://127.0.0.1:" + backendServer.getLocalPort()), Map.of(), Map.of()));
            var listener = new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), catalog);
            listener.setPlacement(player -> CompletableFuture.completedFuture(
                    Optional.of(PlacementDecision.select("lobby"))));
            try {
                int port = ((InetSocketAddress) listener.start().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).localAddress()).getPort();
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                    client.setSoTimeout(5000);
                    sendLogin(client, username);
                    assertEquals(2, readVarInt(readFrame(new DataInputStream(client.getInputStream()))));
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (listener.online().isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
                    assertEquals(1, listener.online().size());

                    listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
                    assertEquals(-1, client.getInputStream().read());
                    backendClosed.get(5, TimeUnit.SECONDS);
                    assertTrue(listener.online().isEmpty());
                    assertTrue(listener.allSessions().isEmpty());
                    assertEquals(0, listener.onlineCount());
                }
            } finally {
                listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void initialHostnameResolutionDoesNotBlockPlayerEventLoop() throws Exception {
        String username = "DnsPlayer";
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
        var catalog = new InMemoryBackendCatalog();
        var resolver = new DeferredBackendResolver("backend.test");
        try (ServerSocket backendServer = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            var releaseBackend = new java.util.concurrent.CountDownLatch(1);
            var backendDone = new CompletableFuture<Void>();
            Thread backendThread = new Thread(() -> {
                try (Socket socket = backendServer.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    readFrame(input);
                    readFrame(input);
                    ByteArrayOutputStream success = new ByteArrayOutputStream();
                    writeVarInt(success, 2);
                    writeString(success, uuid.toString());
                    writeString(success, username);
                    writeFrame(output, success.toByteArray());
                    releaseBackend.await(5, TimeUnit.SECONDS);
                    backendDone.complete(null);
                } catch (Throwable failure) { backendDone.completeExceptionally(failure); }
            }, "fake-dns-backend");
            backendThread.setDaemon(true);
            backendThread.start();
            catalog.register(new BackendRegistration(new BackendId("lobby"), new BackendOwner("static", 0),
                    URI.create("tcp://backend.test:" + backendServer.getLocalPort()), Map.of(), Map.of()));
            var listener = new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                    catalog, null, Duration.ofSeconds(15), Duration.ofSeconds(15), Duration.ofSeconds(15), resolver);
            listener.setPlacement(player -> CompletableFuture.completedFuture(
                    Optional.of(PlacementDecision.select("lobby"))));
            try {
                int port = ((InetSocketAddress) listener.start().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).localAddress()).getPort();
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                    client.setSoTimeout(5000);
                    sendLogin(client, username);
                    var resolution = resolver.pending().get(5, TimeUnit.SECONDS);
                    resolution.executor().submit(() -> { }).get(1, TimeUnit.SECONDS);
                    resolution.release();
                    assertEquals(2, readVarInt(readFrame(new DataInputStream(client.getInputStream()))));
                }
            } finally {
                releaseBackend.countDown();
                listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
                backendDone.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void numericBackendAddressConnectsWithoutDnsResolver() throws Exception {
        String username = "NumericBackend";
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
        var catalog = new InMemoryBackendCatalog();
        var resolver = new DeferredBackendResolver("127.0.0.1");
        try (ServerSocket backendServer = new ServerSocket(0, 8,
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1}))) {
            var releaseBackend = new CountDownLatch(1);
            var backendDone = new CompletableFuture<Void>();
            Thread backendThread = new Thread(() -> {
                try (Socket socket = backendServer.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    readFrame(input);
                    readFrame(input);
                    ByteArrayOutputStream success = new ByteArrayOutputStream();
                    writeVarInt(success, 2);
                    writeString(success, uuid.toString());
                    writeString(success, username);
                    writeFrame(output, success.toByteArray());
                    releaseBackend.await(5, TimeUnit.SECONDS);
                    backendDone.complete(null);
                } catch (Throwable failure) { backendDone.completeExceptionally(failure); }
            }, "fake-numeric-backend");
            backendThread.setDaemon(true);
            backendThread.start();
            catalog.register(new BackendRegistration(new BackendId("lobby"), new BackendOwner("static", 0),
                    URI.create("tcp://127.0.0.1:" + backendServer.getLocalPort()), Map.of(), Map.of()));
            var listener = new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                    catalog, null, Duration.ofSeconds(15), Duration.ofSeconds(15), Duration.ofSeconds(15), resolver);
            listener.setPlacement(player -> CompletableFuture.completedFuture(
                    Optional.of(PlacementDecision.select("lobby"))));
            try {
                int port = ((InetSocketAddress) listener.start().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).localAddress()).getPort();
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                    client.setSoTimeout(1000);
                    sendLogin(client, username);
                    assertEquals(2, readVarInt(readFrame(new DataInputStream(client.getInputStream()))));
                    assertFalse(resolver.pending().isDone(), "numeric IP must bypass DNS resolution");
                }
                releaseBackend.countDown();
                backendDone.get(5, TimeUnit.SECONDS);
            } finally {
                releaseBackend.countDown();
                listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
                backendServer.close();
            }
        }
    }

    @Test
    void placementRejectionSendsItsReasonAsALoginDisconnect() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        var listener = new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), catalog);
        String reason = "空岛 \"alpha\" 正在唤醒";
        listener.setPlacement(player -> CompletableFuture.completedFuture(
                Optional.of(PlacementDecision.reject(reason))));
        try {
            int port = ((InetSocketAddress) listener.start().toCompletableFuture()
                    .get(5, TimeUnit.SECONDS).localAddress()).getPort();
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                client.setSoTimeout(5000);
                sendLogin(client, "WaitingPlayer");
                DataInputStream input = new DataInputStream(client.getInputStream());
                var packet = new java.io.ByteArrayInputStream(readFrame(input));
                assertEquals(0, readVarInt(packet));
                String component = readString(packet, 32767);
                assertEquals(reason, new ObjectMapper().readTree(component).path("text").asText());
                assertEquals(0, packet.available());
                assertEquals(-1, input.read());
            }
            assertTrue(listener.online().isEmpty());
            assertTrue(catalog.snapshot().isEmpty());
        } finally {
            listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void initialPlacementCanOutlastTheHandshakeDeadline() throws Exception {
        String username = "SlowPlacement";
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
        var catalog = new InMemoryBackendCatalog();
        try (ServerSocket backendServer = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            var releaseBackend = new java.util.concurrent.CountDownLatch(1);
            var backendDone = new CompletableFuture<Void>();
            Thread backendThread = new Thread(() -> {
                try (Socket socket = backendServer.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    readFrame(input);
                    readFrame(input);
                    ByteArrayOutputStream success = new ByteArrayOutputStream();
                    writeVarInt(success, 2);
                    writeString(success, uuid.toString());
                    writeString(success, username);
                    writeFrame(output, success.toByteArray());
                    releaseBackend.await(5, TimeUnit.SECONDS);
                    backendDone.complete(null);
                } catch (Throwable failure) {
                    backendDone.completeExceptionally(failure);
                }
            }, "fake-slow-placement-backend");
            backendThread.setDaemon(true);
            backendThread.start();
            catalog.register(new BackendRegistration(new BackendId("lobby"), new BackendOwner("static", 0),
                    URI.create("tcp://127.0.0.1:" + backendServer.getLocalPort()), Map.of(), Map.of()));
            var listener = new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                    catalog, null, Duration.ofMillis(300), Duration.ofSeconds(1));
            listener.setPlacement(player -> {
                var decision = new CompletableFuture<Optional<PlacementDecision>>();
                CompletableFuture.delayedExecutor(600, TimeUnit.MILLISECONDS)
                        .execute(() -> decision.complete(Optional.of(PlacementDecision.select("lobby"))));
                return decision;
            });
            try {
                int port = ((InetSocketAddress) listener.start().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).localAddress()).getPort();
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                    client.setSoTimeout(5000);
                    sendLogin(client, username);
                    assertEquals(2, readVarInt(readFrame(new DataInputStream(client.getInputStream()))));
                }
            } finally {
                releaseBackend.countDown();
                listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
                backendDone.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void backendLoginTimeoutSendsDisconnectReason() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        try (ServerSocket backendServer = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            var releaseBackend = new java.util.concurrent.CountDownLatch(1);
            var backendAccepted = new CompletableFuture<Void>();
            var backendDone = new CompletableFuture<Void>();
            Thread backendThread = new Thread(() -> {
                try (Socket socket = backendServer.accept()) {
                    backendAccepted.complete(null);
                    releaseBackend.await(5, TimeUnit.SECONDS);
                    backendDone.complete(null);
                } catch (Throwable failure) {
                    backendDone.completeExceptionally(failure);
                }
            }, "fake-silent-login-backend");
            backendThread.setDaemon(true);
            backendThread.start();
            catalog.register(new BackendRegistration(new BackendId("lobby"), new BackendOwner("static", 0),
                    URI.create("tcp://127.0.0.1:" + backendServer.getLocalPort()), Map.of(), Map.of()));
            var listener = new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                    catalog, null, Duration.ofSeconds(1), Duration.ofSeconds(1));
            listener.setPlacement(player -> CompletableFuture.completedFuture(
                    Optional.of(PlacementDecision.select("lobby"))));
            try {
                int port = ((InetSocketAddress) listener.start().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).localAddress()).getPort();
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                    client.setSoTimeout(5000);
                    sendLogin(client, "WaitingBackend");
                    backendAccepted.get(5, TimeUnit.SECONDS);
                    DataInputStream input = new DataInputStream(client.getInputStream());
                    var packet = new java.io.ByteArrayInputStream(readFrame(input));
                    assertEquals(0, readVarInt(packet));
                    String component = readString(packet, 32767);
                    assertEquals("Login timed out.", new ObjectMapper().readTree(component).path("text").asText());
                    assertEquals(0, packet.available());
                    assertEquals(-1, input.read());
                }
            } finally {
                releaseBackend.countDown();
                backendServer.close();
                listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
                if (backendAccepted.isDone()) backendDone.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void stalledInitialPlayHandshakeClosesSession() throws Exception {
        String username = "StalledWorld";
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
        var catalog = new InMemoryBackendCatalog();
        try (ServerSocket backendServer = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            var backendClosed = new CompletableFuture<Void>();
            Thread backendThread = new Thread(() -> {
                try (Socket socket = backendServer.accept()) {
                    socket.setSoTimeout(5000);
                    var input = new DataInputStream(socket.getInputStream());
                    var output = new DataOutputStream(socket.getOutputStream());
                    readFrame(input);
                    readFrame(input);
                    var success = new ByteArrayOutputStream();
                    writeVarInt(success, 2);
                    writeString(success, uuid.toString());
                    writeString(success, username);
                    writeFrame(output, success.toByteArray());
                    assertEquals(-1, input.read());
                    backendClosed.complete(null);
                } catch (Throwable failure) {
                    backendClosed.completeExceptionally(failure);
                }
            }, "fake-stalled-world-backend");
            backendThread.setDaemon(true);
            backendThread.start();
            var registered = catalog.register(new BackendRegistration(new BackendId("lobby"),
                    new BackendOwner("static", 0),
                    URI.create("tcp://127.0.0.1:" + backendServer.getLocalPort()), Map.of(), Map.of()));
            var listener = new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                    catalog, null, Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5),
                    Duration.ofMillis(250));
            listener.setPlacement(player -> CompletableFuture.completedFuture(
                    Optional.of(PlacementDecision.select("lobby"))));
            try {
                int port = ((InetSocketAddress) listener.start().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).localAddress()).getPort();
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                    client.setSoTimeout(5000);
                    sendLogin(client, username);
                    var input = new DataInputStream(client.getInputStream());
                    assertEquals(2, readVarInt(readFrame(input)));
                    assertEquals(-1, input.read());
                }
                backendClosed.get(5, TimeUnit.SECONDS);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while ((!listener.online().isEmpty() || !listener.allSessions().isEmpty())
                        && System.nanoTime() < deadline) Thread.sleep(5);
                assertTrue(listener.online().isEmpty());
                assertTrue(listener.allSessions().isEmpty());
            } finally {
                listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void completedInitialPlayHandshakeDoesNotExpire() throws Exception {
        String username = "ReadyWorld";
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
        var catalog = new InMemoryBackendCatalog();
        try (ServerSocket backendServer = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            var backendDone = new CompletableFuture<Void>();
            Thread backendThread = new Thread(() -> {
                try (Socket socket = backendServer.accept()) {
                    socket.setSoTimeout(5000);
                    var input = new DataInputStream(socket.getInputStream());
                    var output = new DataOutputStream(socket.getOutputStream());
                    readFrame(input);
                    readFrame(input);
                    var success = new ByteArrayOutputStream();
                    writeVarInt(success, 2);
                    writeString(success, uuid.toString());
                    writeString(success, username);
                    writeFrame(output, success.toByteArray());
                    writeFrame(output, new byte[]{0x01, 0, 0, 0, 42, 0, 0, 1, 20,
                            7, 'd', 'e', 'f', 'a', 'u', 'l', 't'});
                    var position = new ByteArrayOutputStream();
                    var positionData = new DataOutputStream(position);
                    positionData.writeByte(0x08);
                    positionData.writeDouble(0);
                    positionData.writeDouble(64);
                    positionData.writeDouble(0);
                    positionData.writeFloat(0);
                    positionData.writeFloat(0);
                    positionData.writeByte(0);
                    writeFrame(output, position.toByteArray());
                    Thread.sleep(750);
                    writeFrame(output, new byte[]{0x03, 0x55});
                    backendDone.complete(null);
                } catch (Throwable failure) {
                    backendDone.completeExceptionally(failure);
                }
            }, "fake-ready-world-backend");
            backendThread.setDaemon(true);
            backendThread.start();
            catalog.register(new BackendRegistration(new BackendId("lobby"), new BackendOwner("static", 0),
                    URI.create("tcp://127.0.0.1:" + backendServer.getLocalPort()), Map.of(), Map.of()));
            var listener = new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                    catalog, null, Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5),
                    Duration.ofMillis(500));
            listener.setPlacement(player -> CompletableFuture.completedFuture(
                    Optional.of(PlacementDecision.select("lobby"))));
            try {
                int port = ((InetSocketAddress) listener.start().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).localAddress()).getPort();
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                    client.setSoTimeout(5000);
                    sendLogin(client, username);
                    var input = new DataInputStream(client.getInputStream());
                    assertEquals(2, readVarInt(readFrame(input)));
                    assertEquals(1, readVarInt(readFrame(input)));
                    assertEquals(8, readVarInt(readFrame(input)));
                    assertArrayEquals(new byte[]{0x03, 0x55}, readFrame(input));
                }
                backendDone.get(5, TimeUnit.SECONDS);
            } finally {
                listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void rejectsSecondConnectionForSamePlayerIdentity() throws Exception {
        String username = "SamePlayer";
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
        var catalog = new InMemoryBackendCatalog();
        try (ServerSocket backendServer = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            var releaseBackend = new java.util.concurrent.CountDownLatch(1);
            var backendDone = new CompletableFuture<Void>();
            Thread backendThread = new Thread(() -> {
                try (Socket socket = backendServer.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    readFrame(input);
                    readFrame(input);
                    ByteArrayOutputStream success = new ByteArrayOutputStream();
                    writeVarInt(success, 2);
                    writeString(success, uuid.toString());
                    writeString(success, username);
                    writeFrame(output, success.toByteArray());
                    releaseBackend.await(5, TimeUnit.SECONDS);
                    backendDone.complete(null);
                } catch (Throwable failure) {
                    backendDone.completeExceptionally(failure);
                }
            }, "fake-duplicate-backend");
            backendThread.setDaemon(true);
            backendThread.start();
            var backend = catalog.register(new BackendRegistration(new BackendId("lobby"),
                    new BackendOwner("static", 0), URI.create("tcp://127.0.0.1:" + backendServer.getLocalPort()), Map.of(), Map.of()));
            var listener = new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), catalog);
            listener.setPlacement(player -> CompletableFuture.completedFuture(Optional.of(PlacementDecision.select("lobby"))));
            try {
                int port = ((InetSocketAddress) listener.start().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).localAddress()).getPort();
                try (Socket first = new Socket(InetAddress.getLoopbackAddress(), port);
                     Socket second = new Socket(InetAddress.getLoopbackAddress(), port)) {
                    first.setSoTimeout(5000);
                    second.setSoTimeout(5000);
                    sendLogin(first, username);
                    assertEquals(2, readVarInt(readFrame(new DataInputStream(first.getInputStream()))));
                    sendLogin(second, username);
                    assertEquals(-1, second.getInputStream().read());
                    assertEquals(1, listener.online().size());
                }
            } finally {
                releaseBackend.countDown();
                listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
                backendDone.get(5, TimeUnit.SECONDS);
            }
        }
    }

    private static void sendLogin(Socket client, String username) throws Exception {
        sendLogin(client, username, "localhost");
    }

    private static void sendLogin(Socket client, String username, String host) throws Exception {
        DataOutputStream output = new DataOutputStream(client.getOutputStream());
        ByteArrayOutputStream hello = new ByteArrayOutputStream();
        writeVarInt(hello, 0);
        writeVarInt(hello, 5);
        writeString(hello, host);
        hello.write(0); hello.write(1);
        writeVarInt(hello, 2);
        writeFrame(output, hello.toByteArray());
        ByteArrayOutputStream login = new ByteArrayOutputStream();
        writeVarInt(login, 0);
        writeString(login, username);
        writeFrame(output, login.toByteArray());
    }

    @Test
    void onlineLoginForwardsVerifiedIdentityAndEncryptsPlayStream() throws Exception {
        UUID uuid = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        var catalog = new InMemoryBackendCatalog();
        try (ServerSocket backendServer = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            var backendDone = new CompletableFuture<Void>();
            Thread backendThread = new Thread(() -> {
                try (Socket socket = backendServer.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    var handshake = new java.io.ByteArrayInputStream(readFrame(input));
                    assertEquals(0, readVarInt(handshake));
                    assertEquals(5, readVarInt(handshake));
                    String host = readString(handshake, 32767);
                    String[] fields = host.split("\u0000", -1);
                    assertEquals("localhost", fields[0]);
                    assertEquals("127.0.0.1", fields[1]);
                    assertEquals("12345678123412341234123456789abc", fields[2]);
                    handshake.readNBytes(2);
                    assertEquals(2, readVarInt(handshake));
                    var login = new java.io.ByteArrayInputStream(readFrame(input));
                    assertEquals(0, readVarInt(login));
                    assertEquals("Alice", readString(login, 16));
                    ByteArrayOutputStream success = new ByteArrayOutputStream();
                    writeVarInt(success, 2);
                    writeString(success, uuid.toString());
                    writeString(success, "Alice");
                    output.write(framed(success.toByteArray()));
                    output.write(framed(new byte[]{0x02, 0x55}));
                    output.flush();
                    assertArrayEquals(new byte[]{0x01, 0x33}, readFrame(input));
                    writeFrame(output, new byte[]{0x03, 0x44});
                    backendDone.complete(null);
                } catch (Throwable failure) {
                    backendDone.completeExceptionally(failure);
                }
            }, "fake-online-backend");
            backendThread.setDaemon(true);
            backendThread.start();

            catalog.register(new BackendRegistration(new BackendId("lobby"), new BackendOwner("static", 0),
                    URI.create("tcp://127.0.0.1:" + backendServer.getLocalPort()), Map.of(), Map.of()));
            var listener = new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                    catalog, (name, hash, ip) -> {
                        assertEquals("Alice", name);
                        assertEquals("127.0.0.1", ip);
                        assertTrue(!hash.isBlank());
                        return CompletableFuture.completedFuture(Optional.of(new VerifiedProfile(uuid, "Alice", java.util.List.of())));
                    });
            listener.setPlacement(player -> CompletableFuture.completedFuture(Optional.of(PlacementDecision.select("lobby"))));
            try {
                var bound = listener.start().toCompletableFuture().get(5, TimeUnit.SECONDS);
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(),
                        ((InetSocketAddress) bound.localAddress()).getPort())) {
                    client.setTcpNoDelay(true);
                    client.setSoTimeout(5000);
                    DataInputStream clearInput = new DataInputStream(client.getInputStream());
                    DataOutputStream clearOutput = new DataOutputStream(client.getOutputStream());
                    ByteArrayOutputStream hello = new ByteArrayOutputStream();
                    writeVarInt(hello, 0); writeVarInt(hello, 5); writeString(hello, "localhost");
                    hello.write(0); hello.write(1); writeVarInt(hello, 2);
                    writeFrame(clearOutput, hello.toByteArray());
                    ByteArrayOutputStream login = new ByteArrayOutputStream();
                    writeVarInt(login, 0); writeString(login, "Alice");
                    writeFrame(clearOutput, login.toByteArray());

                    var requestBytes = Unpooled.wrappedBuffer(readFrame(clearInput));
                    MinecraftEncryptionRequest request;
                    try { request = MinecraftEncryptionRequest.decode(requestBytes); }
                    finally { requestBytes.release(); }
                    byte[] secret = new byte[16];
                    java.util.Arrays.fill(secret, (byte) 0x42);
                    var publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(request.publicKey()));
                    Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                    rsa.init(Cipher.ENCRYPT_MODE, publicKey);
                    var response = new MinecraftEncryptionResponse(rsa.doFinal(secret), rsa.doFinal(request.verifyToken()));
                    var encoded = response.encode(UnpooledByteBufAllocator.DEFAULT);
                    try {
                        byte[] payload = new byte[encoded.readableBytes()];
                        encoded.readBytes(payload);
                        writeFrame(clearOutput, payload);
                    } finally { encoded.release(); }

                    DataInputStream encryptedInput = new DataInputStream(decryptingInput(client.getInputStream(),
                            aes(secret, Cipher.DECRYPT_MODE)));
                    var success = new java.io.ByteArrayInputStream(readFrame(encryptedInput));
                    assertEquals(2, readVarInt(success));
                    assertEquals(uuid.toString(), readString(success, 36));
                    assertEquals("Alice", readString(success, 16));
                    assertArrayEquals(new byte[]{0x02, 0x55}, readFrame(encryptedInput));
                    byte[] encryptedPlay = aes(secret, Cipher.ENCRYPT_MODE).update(framed(new byte[]{0x01, 0x33}));
                    assertEquals(3, encryptedPlay.length);
                    clearOutput.write(encryptedPlay);
                    clearOutput.flush();
                    backendDone.get(5, TimeUnit.SECONDS);
                    assertArrayEquals(new byte[]{0x03, 0x44}, readFrame(encryptedInput));
                }
            } finally {
                listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void onlineTransferToForgeForwardsVerifiedIdentityAndKeepsClientEncrypted() throws Exception {
        UUID uuid = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        var catalog = new InMemoryBackendCatalog();
        try (ServerSocket oldServer = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
             ServerSocket replacement = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            var oldClosed = new CompletableFuture<Void>();
            var replacementRelayed = new CompletableFuture<Void>();
            Thread oldThread = new Thread(() -> {
                try (Socket socket = oldServer.accept()) {
                    socket.setSoTimeout(5000);
                    var input = new DataInputStream(socket.getInputStream());
                    var output = new DataOutputStream(socket.getOutputStream());
                    acceptForwardedLogin(input, uuid);
                    sendOnlineBackendPlayStart(output, uuid, 100);
                    assertEquals(-1, input.read());
                    oldClosed.complete(null);
                } catch (Throwable failure) { oldClosed.completeExceptionally(failure); }
            }, "online-transfer-old-backend");
            oldThread.setDaemon(true);
            oldThread.start();
            Thread replacementThread = new Thread(() -> {
                try (Socket socket = replacement.accept()) {
                    socket.setSoTimeout(5000);
                    var input = new DataInputStream(socket.getInputStream());
                    var output = new DataOutputStream(socket.getOutputStream());
                    acceptForwardedLogin(input, uuid);
                    sendOnlineBackendLoginSuccess(output, uuid);
                    writeFrame(output, forgeRegistration(true));
                    writeFrame(output, forgeHello(7));
                    assertArrayEquals(forgeRegistration(false), readFrame(input));
                    assertArrayEquals(forgeMessage(false, new byte[]{1, 2}), readFrame(input));
                    assertArrayEquals(forgeMessage(false, new byte[]{2, 0}), readFrame(input));
                    writeFrame(output, forgeMessage(true, new byte[]{2, 0}));
                    assertArrayEquals(forgeMessage(false, new byte[]{(byte) 0xff, 2}), readFrame(input));
                    writeFrame(output, forgeMessage(true, new byte[]{3, 0, 0, 0}));
                    writeFrame(output, forgeMessage(true, new byte[]{(byte) 0xff, 2}));
                    assertArrayEquals(forgeMessage(false, new byte[]{(byte) 0xff, 3}), readFrame(input));
                    assertArrayEquals(forgeMessage(false, new byte[]{(byte) 0xff, 4}), readFrame(input));
                    writeFrame(output, forgeMessage(true, new byte[]{(byte) 0xff, 3}));
                    assertArrayEquals(forgeMessage(false, new byte[]{(byte) 0xff, 5}), readFrame(input));
                    sendOnlineBackendWorld(output, 200);
                    assertArrayEquals(new byte[]{0x01, 0x33}, readFrame(input));
                    writeFrame(output, new byte[]{0x03, 0x44});
                    replacementRelayed.complete(null);
                    while (input.read() != -1) { }
                } catch (Throwable failure) { replacementRelayed.completeExceptionally(failure); }
            }, "online-transfer-replacement-backend");
            replacementThread.setDaemon(true);
            replacementThread.start();

            var owner = new BackendOwner("static", 0);
            catalog.register(new BackendRegistration(new BackendId("old"), owner,
                    URI.create("tcp://127.0.0.1:" + oldServer.getLocalPort()), Map.of(), Map.of()));
            catalog.register(new BackendRegistration(new BackendId("new"), owner,
                    URI.create("tcp://127.0.0.1:" + replacement.getLocalPort()), Map.of(), Map.of()));
            var listener = new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                    catalog, (name, hash, ip) -> CompletableFuture.completedFuture(
                            Optional.of(new VerifiedProfile(uuid, "Alice", List.of()))));
            listener.setPlacement(player -> CompletableFuture.completedFuture(
                    Optional.of(PlacementDecision.select("old"))));
            try {
                int port = ((InetSocketAddress) listener.start().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).localAddress()).getPort();
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                    client.setTcpNoDelay(true);
                    client.setSoTimeout(5000);
                    var clearInput = new DataInputStream(client.getInputStream());
                    var clearOutput = new DataOutputStream(client.getOutputStream());
                    ByteArrayOutputStream hello = new ByteArrayOutputStream();
                    writeVarInt(hello, 0); writeVarInt(hello, 5); writeString(hello, "localhost");
                    hello.write(0); hello.write(1); writeVarInt(hello, 2);
                    writeFrame(clearOutput, hello.toByteArray());
                    ByteArrayOutputStream login = new ByteArrayOutputStream();
                    writeVarInt(login, 0); writeString(login, "Alice");
                    writeFrame(clearOutput, login.toByteArray());

                    var requestBytes = Unpooled.wrappedBuffer(readFrame(clearInput));
                    MinecraftEncryptionRequest request;
                    try { request = MinecraftEncryptionRequest.decode(requestBytes); }
                    finally { requestBytes.release(); }
                    byte[] secret = new byte[16];
                    java.util.Arrays.fill(secret, (byte) 0x42);
                    var publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(request.publicKey()));
                    Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                    rsa.init(Cipher.ENCRYPT_MODE, publicKey);
                    var response = new MinecraftEncryptionResponse(rsa.doFinal(secret), rsa.doFinal(request.verifyToken()));
                    var encoded = response.encode(UnpooledByteBufAllocator.DEFAULT);
                    try {
                        byte[] payload = new byte[encoded.readableBytes()];
                        encoded.readBytes(payload);
                        writeFrame(clearOutput, payload);
                    } finally { encoded.release(); }

                    var encryptedInput = new DataInputStream(decryptingInput(client.getInputStream(),
                            aes(secret, Cipher.DECRYPT_MODE)));
                    Cipher encryptor = aes(secret, Cipher.ENCRYPT_MODE);
                    var success = new java.io.ByteArrayInputStream(readFrame(encryptedInput));
                    assertEquals(2, readVarInt(success));
                    assertEquals(uuid.toString(), readString(success, 36));
                    assertEquals("Alice", readString(success, 16));
                    assertEquals(1, readVarInt(new java.io.ByteArrayInputStream(readFrame(encryptedInput))));
                    assertArrayEquals(new byte[]{0x08}, readFrame(encryptedInput));

                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (listener.online().isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
                    assertEquals(1, listener.online().size());
                    var player = listener.online().get(0);
                    var pendingTransfer = listener.transfer(player.identity(), "new").toCompletableFuture();
                    byte[] reset = readFrame(encryptedInput);
                    assertEquals(0x3f, readVarInt(new java.io.ByteArrayInputStream(reset)));
                    assertEquals((byte) 0xfe, reset[reset.length - 1]);
                    assertArrayEquals(forgeRegistration(true), readFrame(encryptedInput));
                    assertArrayEquals(forgeHello(7), readFrame(encryptedInput));
                    writeEncryptedFrame(clearOutput, encryptor, forgeRegistration(false));
                    writeEncryptedFrame(clearOutput, encryptor, forgeMessage(false, new byte[]{1, 2}));
                    writeEncryptedFrame(clearOutput, encryptor, forgeMessage(false, new byte[]{2, 0}));
                    assertArrayEquals(forgeMessage(true, new byte[]{2, 0}), readFrame(encryptedInput));
                    writeEncryptedFrame(clearOutput, encryptor, forgeMessage(false, new byte[]{(byte) 0xff, 2}));
                    assertArrayEquals(forgeMessage(true, new byte[]{3, 0, 0, 0}), readFrame(encryptedInput));
                    assertArrayEquals(forgeMessage(true, new byte[]{(byte) 0xff, 2}), readFrame(encryptedInput));
                    writeEncryptedFrame(clearOutput, encryptor, forgeMessage(false, new byte[]{(byte) 0xff, 3}));
                    writeEncryptedFrame(clearOutput, encryptor, forgeMessage(false, new byte[]{(byte) 0xff, 4}));
                    assertArrayEquals(forgeMessage(true, new byte[]{(byte) 0xff, 3}), readFrame(encryptedInput));
                    writeEncryptedFrame(clearOutput, encryptor, forgeMessage(false, new byte[]{(byte) 0xff, 5}));
                    var transfer = pendingTransfer.get(5, TimeUnit.SECONDS);
                    assertEquals(dev.strataproxy.api.TransferStatus.NETWORK_READY, transfer.status(),
                            transfer.detail().orElse(""));
                    assertEquals(7, readVarInt(new java.io.ByteArrayInputStream(readFrame(encryptedInput))));
                    assertEquals(7, readVarInt(new java.io.ByteArrayInputStream(readFrame(encryptedInput))));
                    assertArrayEquals(new byte[]{0x08}, readFrame(encryptedInput));
                    writeEncryptedFrame(clearOutput, encryptor, new byte[]{0x01, 0x33});
                    replacementRelayed.get(5, TimeUnit.SECONDS);
                    assertArrayEquals(new byte[]{0x03, 0x44}, readFrame(encryptedInput));
                    assertEquals("new", listener.find(player.identity()).orElseThrow().currentServer().orElseThrow());
                    oldClosed.get(5, TimeUnit.SECONDS);
                }
            } finally { listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS); }
        }
    }

    private static void acceptForwardedLogin(DataInputStream input, UUID uuid) throws Exception {
        var handshake = new java.io.ByteArrayInputStream(readFrame(input));
        assertEquals(0, readVarInt(handshake));
        assertEquals(5, readVarInt(handshake));
        String[] fields = readString(handshake, 32767).split(String.valueOf('\0'), -1);
        assertEquals(3, fields.length);
        assertEquals("localhost", fields[0]);
        assertEquals("127.0.0.1", fields[1]);
        assertEquals(uuid.toString().replace("-", ""), fields[2]);
        handshake.readNBytes(2);
        assertEquals(2, readVarInt(handshake));
        var login = new java.io.ByteArrayInputStream(readFrame(input));
        assertEquals(0, readVarInt(login));
        assertEquals("Alice", readString(login, 16));
    }

    private static void sendOnlineBackendPlayStart(DataOutputStream output, UUID uuid, int entityId) throws Exception {
        sendOnlineBackendLoginSuccess(output, uuid);
        sendOnlineBackendWorld(output, entityId);
    }

    private static void sendOnlineBackendLoginSuccess(DataOutputStream output, UUID uuid) throws Exception {
        ByteArrayOutputStream success = new ByteArrayOutputStream();
        writeVarInt(success, 2);
        writeString(success, uuid.toString());
        writeString(success, "Alice");
        writeFrame(output, success.toByteArray());
    }

    private static void sendOnlineBackendWorld(DataOutputStream output, int entityId) throws Exception {
        ByteArrayOutputStream join = new ByteArrayOutputStream();
        writeVarInt(join, 1);
        var data = new DataOutputStream(join);
        data.writeInt(entityId);
        data.writeByte(0);
        data.writeByte(0);
        data.writeByte(1);
        data.writeByte(20);
        writeString(join, "default");
        writeFrame(output, join.toByteArray());
        writeFrame(output, new byte[]{0x08});
    }

    private static void writeEncryptedFrame(DataOutputStream output, Cipher cipher, byte[] payload) throws Exception {
        output.write(cipher.update(framed(payload)));
        output.flush();
    }

    private static byte[] forgeRegistration(boolean clientbound) throws Exception {
        return forgePacket(clientbound, "REGISTER", "FML|HS\0FML".getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] forgeHello(int dimensionOverride) throws Exception {
        ByteArrayOutputStream hello = new ByteArrayOutputStream();
        var output = new DataOutputStream(hello);
        output.writeByte(0);
        output.writeByte(2);
        output.writeInt(dimensionOverride);
        return forgeMessage(true, hello.toByteArray());
    }

    private static byte[] forgeMessage(boolean clientbound, byte[] handshake) throws Exception {
        return forgePacket(clientbound, "FML|HS", handshake);
    }

    private static byte[] forgePacket(boolean clientbound, String channel, byte[] payload) throws Exception {
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        writeVarInt(packet, clientbound ? 0x3f : 0x17);
        writeString(packet, channel);
        var output = new DataOutputStream(packet);
        output.writeShort(payload.length);
        output.write(payload);
        return packet.toByteArray();
    }

    @Test
    void onlinePlacementRejectionIsEncryptedAndDelivered() throws Exception {
        UUID uuid = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        String reason = "Your island is starting";
        var listener = new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                new InMemoryBackendCatalog(), (name, hash, ip) -> CompletableFuture.completedFuture(
                        Optional.of(new VerifiedProfile(uuid, name, java.util.List.of()))));
        listener.setPlacement(player -> CompletableFuture.completedFuture(
                Optional.of(PlacementDecision.reject(reason))));
        try {
            var bound = listener.start().toCompletableFuture().get(5, TimeUnit.SECONDS);
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(),
                    ((InetSocketAddress) bound.localAddress()).getPort())) {
                client.setSoTimeout(5000);
                DataInputStream clearInput = new DataInputStream(client.getInputStream());
                DataOutputStream clearOutput = new DataOutputStream(client.getOutputStream());
                sendLogin(client, "Alice");

                var requestBytes = Unpooled.wrappedBuffer(readFrame(clearInput));
                MinecraftEncryptionRequest request;
                try { request = MinecraftEncryptionRequest.decode(requestBytes); }
                finally { requestBytes.release(); }
                byte[] secret = new byte[16];
                java.util.Arrays.fill(secret, (byte) 0x42);
                var publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(request.publicKey()));
                Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                rsa.init(Cipher.ENCRYPT_MODE, publicKey);
                var response = new MinecraftEncryptionResponse(rsa.doFinal(secret), rsa.doFinal(request.verifyToken()));
                var encoded = response.encode(UnpooledByteBufAllocator.DEFAULT);
                try {
                    byte[] payload = new byte[encoded.readableBytes()];
                    encoded.readBytes(payload);
                    writeFrame(clearOutput, payload);
                } finally { encoded.release(); }

                DataInputStream encryptedInput = new DataInputStream(decryptingInput(client.getInputStream(),
                        aes(secret, Cipher.DECRYPT_MODE)));
                var packet = new java.io.ByteArrayInputStream(readFrame(encryptedInput));
                assertEquals(0, readVarInt(packet));
                assertEquals(reason, new ObjectMapper().readTree(readString(packet, 32767)).path("text").asText());
                assertEquals(0, packet.available());
                assertEquals(-1, encryptedInput.read());
            }
            assertTrue(listener.online().isEmpty());
        } finally {
            listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void disconnectDuringOnlineVerificationCancelsThePendingRequest() throws Exception {
        var pendingVerification = new CompletableFuture<Optional<VerifiedProfile>>();
        var verificationStarted = new java.util.concurrent.CountDownLatch(1);
        var listener = new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                new InMemoryBackendCatalog(), (name, hash, ip) -> {
                    verificationStarted.countDown();
                    return pendingVerification;
                });
        listener.setPlacement(player -> CompletableFuture.completedFuture(Optional.empty()));
        try {
            int port = ((InetSocketAddress) listener.start().toCompletableFuture()
                    .get(5, TimeUnit.SECONDS).localAddress()).getPort();
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                client.setSoTimeout(5000);
                sendLogin(client, "Alice");
                var requestBytes = Unpooled.wrappedBuffer(readFrame(new DataInputStream(client.getInputStream())));
                MinecraftEncryptionRequest request;
                try { request = MinecraftEncryptionRequest.decode(requestBytes); }
                finally { requestBytes.release(); }
                byte[] secret = new byte[16];
                java.util.Arrays.fill(secret, (byte) 0x42);
                var publicKey = KeyFactory.getInstance("RSA").generatePublic(
                        new X509EncodedKeySpec(request.publicKey()));
                Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                rsa.init(Cipher.ENCRYPT_MODE, publicKey);
                var response = new MinecraftEncryptionResponse(
                        rsa.doFinal(secret), rsa.doFinal(request.verifyToken()));
                var encoded = response.encode(UnpooledByteBufAllocator.DEFAULT);
                try {
                    byte[] payload = new byte[encoded.readableBytes()];
                    encoded.readBytes(payload);
                    writeFrame(new DataOutputStream(client.getOutputStream()), payload);
                } finally { encoded.release(); }
                assertTrue(verificationStarted.await(5, TimeUnit.SECONDS));
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((!pendingVerification.isCancelled() || !listener.allSessions().isEmpty())
                    && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue(pendingVerification.isCancelled());
            assertTrue(listener.allSessions().isEmpty());
        } finally {
            listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void oversizedVerifiedProfileFailsLoginPromptly() throws Exception {
        UUID uuid = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        var properties = java.util.List.of(
                new ProfileProperty("first", "a".repeat(20_000), null),
                new ProfileProperty("second", "b".repeat(20_000), null));
        var catalog = new InMemoryBackendCatalog();
        try (ServerSocket backendServer = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            var registration = catalog.register(new BackendRegistration(new BackendId("lobby"),
                    new BackendOwner("static", 0),
                    URI.create("tcp://127.0.0.1:" + backendServer.getLocalPort()), Map.of(), Map.of()));
            var listener = new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                    catalog, (name, hash, ip) -> CompletableFuture.completedFuture(
                            Optional.of(new VerifiedProfile(uuid, name, properties))));
            listener.setPlacement(player -> CompletableFuture.completedFuture(
                    Optional.of(PlacementDecision.select("lobby"))));
            try {
                int port = ((InetSocketAddress) listener.start().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).localAddress()).getPort();
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                    client.setSoTimeout(2000);
                    sendLogin(client, "Alice");
                    var requestBytes = Unpooled.wrappedBuffer(readFrame(new DataInputStream(client.getInputStream())));
                    MinecraftEncryptionRequest request;
                    try { request = MinecraftEncryptionRequest.decode(requestBytes); }
                    finally { requestBytes.release(); }
                    byte[] secret = new byte[16];
                    java.util.Arrays.fill(secret, (byte) 0x42);
                    var publicKey = KeyFactory.getInstance("RSA").generatePublic(
                            new X509EncodedKeySpec(request.publicKey()));
                    Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                    rsa.init(Cipher.ENCRYPT_MODE, publicKey);
                    var response = new MinecraftEncryptionResponse(
                            rsa.doFinal(secret), rsa.doFinal(request.verifyToken()));
                    var encoded = response.encode(UnpooledByteBufAllocator.DEFAULT);
                    try {
                        byte[] payload = new byte[encoded.readableBytes()];
                        encoded.readBytes(payload);
                        writeFrame(new DataOutputStream(client.getOutputStream()), payload);
                    } finally { encoded.release(); }

                    DataInputStream encryptedInput = new DataInputStream(decryptingInput(client.getInputStream(),
                            aes(secret, Cipher.DECRYPT_MODE)));
                    var packet = new java.io.ByteArrayInputStream(readFrame(encryptedInput));
                    assertEquals(0, readVarInt(packet));
                    assertEquals("Could not prepare backend login.",
                            new ObjectMapper().readTree(readString(packet, 32767)).path("text").asText());
                    assertEquals(-1, encryptedInput.read());
                }
                long cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!listener.allSessions().isEmpty()
                        && System.nanoTime() < cleanupDeadline) Thread.sleep(5);
                assertTrue(listener.allSessions().isEmpty());
            } finally {
                listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    private static Cipher aes(byte[] secret, int mode) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CFB8/NoPadding");
        cipher.init(mode, new SecretKeySpec(secret, "AES"), new IvParameterSpec(secret));
        return cipher;
    }

    private static InputStream decryptingInput(InputStream encrypted, Cipher cipher) {
        return new InputStream() {
            @Override public int read() throws IOException {
                int next = encrypted.read();
                if (next < 0) return -1;
                byte[] decoded = cipher.update(new byte[]{(byte) next});
                if (decoded == null || decoded.length != 1) throw new IOException("CFB8 did not emit one byte");
                return decoded[0] & 0xff;
            }
        };
    }

    @Test
    void backendReadFailureWaitsForAcceptedDisconnectFrame() throws Exception {
        verifyBackendReadFailureDrain(true);
    }

    @Test
    void backendLoginDisconnectSurvivesImmediateBackendClose() throws Exception {
        verifyBackendLoginDisconnectDrain(true);
    }

    @Test
    void failedBackendConnectSendsLoginDisconnectReason() throws Exception {
        int unavailablePort;
        try (ServerSocket unused = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            unavailablePort = unused.getLocalPort();
        }
        var catalog = new InMemoryBackendCatalog();
        catalog.register(new BackendRegistration(new BackendId("lobby"), new BackendOwner("static", 0),
                URI.create("tcp://127.0.0.1:" + unavailablePort)));
        var listener = new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), catalog);
        listener.setPlacement(player -> CompletableFuture.completedFuture(Optional.of(PlacementDecision.select("lobby"))));
        try {
            int port = ((InetSocketAddress) listener.start().toCompletableFuture()
                    .get(5, TimeUnit.SECONDS).localAddress()).getPort();
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                client.setSoTimeout(5000);
                sendLogin(client, "ConnectFailure");
                var response = new java.io.ByteArrayInputStream(readFrame(new DataInputStream(client.getInputStream())));
                assertEquals(0, readVarInt(response));
                assertTrue(readString(response, 32767).contains("Could not connect"));
            }
        } finally {
            listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void backendLoginDisconnectClosesStalledClientWriteAfterDeadline() throws Exception {
        verifyBackendLoginDisconnectDrain(false);
    }

    private void verifyBackendLoginDisconnectDrain(boolean completeClientWrite) throws Exception {
        var catalog = new InMemoryBackendCatalog();
        try (ServerSocket backendServer = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            var backendReady = new CompletableFuture<Void>();
            var sendDisconnect = new CompletableFuture<Void>();
            var backendDone = new CompletableFuture<Void>();
            var disconnectBody = new ByteArrayOutputStream();
            writeVarInt(disconnectBody, 0);
            writeString(disconnectBody, "{\"text\":\"Backend refused login\"}");
            byte[] disconnect = disconnectBody.toByteArray();
            var backendThread = new Thread(() -> {
                try (Socket socket = backendServer.accept()) {
                    socket.setSoTimeout(5000);
                    var input = new DataInputStream(socket.getInputStream());
                    var output = new DataOutputStream(socket.getOutputStream());
                    readFrame(input);
                    readFrame(input);
                    backendReady.complete(null);
                    sendDisconnect.get(5, TimeUnit.SECONDS);
                    writeFrame(output, disconnect);
                } catch (Throwable failure) { backendDone.completeExceptionally(failure); return; }
                backendDone.complete(null);
            }, "fake-login-disconnect-backend");
            backendThread.setDaemon(true);
            backendThread.start();

            catalog.register(new BackendRegistration(new BackendId("lobby"), new BackendOwner("static", 0),
                    URI.create("tcp://127.0.0.1:" + backendServer.getLocalPort())));
            var listener = new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), catalog);
            listener.setPlacement(player -> CompletableFuture.completedFuture(Optional.of(PlacementDecision.select("lobby"))));
            var heldContext = new AtomicReference<ChannelHandlerContext>();
            var heldMessage = new AtomicReference<Object>();
            var heldPromise = new AtomicReference<ChannelPromise>();
            var heldWrite = new CompletableFuture<Void>();
            try {
                int port = ((InetSocketAddress) listener.start().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).localAddress()).getPort();
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                    client.setSoTimeout(5000);
                    sendLogin(client, "RejectedPlayer");
                    backendReady.get(5, TimeUnit.SECONDS);
                    Session session = listener.allSessions().iterator().next();
                    var frontendField = Session.class.getDeclaredField("frontend");
                    frontendField.setAccessible(true);
                    Channel frontend = (Channel) frontendField.get(session);
                    var backendField = Session.class.getDeclaredField("backend");
                    backendField.setAccessible(true);
                    Channel backendChannel = (Channel) backendField.get(session);
                    frontend.eventLoop().submit(() -> frontend.pipeline().addFirst("hold-login-disconnect",
                            new ChannelOutboundHandlerAdapter() {
                                @Override public void write(ChannelHandlerContext ctx, Object message,
                                                            ChannelPromise promise) {
                                    heldContext.set(ctx);
                                    heldMessage.set(message);
                                    heldPromise.set(promise);
                                    heldWrite.complete(null);
                                }
                            })).get(5, TimeUnit.SECONDS);
                    sendDisconnect.complete(null);
                    heldWrite.get(5, TimeUnit.SECONDS);
                    backendDone.get(5, TimeUnit.SECONDS);
                    assertTrue(backendChannel.closeFuture().await(5, TimeUnit.SECONDS));
                    backendChannel.eventLoop().submit(() -> { }).get(5, TimeUnit.SECONDS);
                    assertTrue(frontend.isOpen(), "backend EOF dropped an accepted login disconnect");
                    var input = new DataInputStream(client.getInputStream());
                    if (completeClientWrite) {
                        frontend.eventLoop().submit(() -> heldContext.get().writeAndFlush(
                                heldMessage.getAndSet(null), heldPromise.get())).get(5, TimeUnit.SECONDS);
                        assertArrayEquals(disconnect, readFrame(input));
                    } else {
                        assertTrue(frontend.closeFuture().await(7, TimeUnit.SECONDS),
                                "the stalled login disconnect write must not keep the session open forever");
                    }
                    assertEquals(-1, input.read());
                    long cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!listener.allSessions().isEmpty()
                            && System.nanoTime() < cleanupDeadline) Thread.sleep(5);
                    assertTrue(listener.allSessions().isEmpty());
                }
            } finally {
                sendDisconnect.complete(null);
                ReferenceCountUtil.release(heldMessage.getAndSet(null));
                listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
                backendDone.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void backendReadFailureClosesStalledClientWriteAfterDeadline() throws Exception {
        verifyBackendReadFailureDrain(false);
    }

    private void verifyBackendReadFailureDrain(boolean completeClientWrite) throws Exception {
        String username = "DisconnectPlayer";
        UUID offlineId = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
        var catalog = new InMemoryBackendCatalog();
        try (ServerSocket backendServer = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            var sendDisconnect = new CompletableFuture<Void>();
            var backendDone = new CompletableFuture<Void>();
            ByteArrayOutputStream disconnectBody = new ByteArrayOutputStream();
            writeVarInt(disconnectBody, 0x40);
            writeString(disconnectBody, "{\"text\":\"Server restarting\"}");
            byte[] disconnect = disconnectBody.toByteArray();
            var backendThread = new Thread(() -> {
                try (Socket socket = backendServer.accept()) {
                    socket.setSoTimeout(5000);
                    var input = new DataInputStream(socket.getInputStream());
                    var output = new DataOutputStream(socket.getOutputStream());
                    readFrame(input);
                    readFrame(input);
                    ByteArrayOutputStream success = new ByteArrayOutputStream();
                    writeVarInt(success, 2);
                    writeString(success, offlineId.toString());
                    writeString(success, username);
                    writeFrame(output, success.toByteArray());
                    assertArrayEquals(new byte[]{0x03, 0x22}, readFrame(input));
                    writeFrame(output, new byte[]{0x03, 0x44});
                    sendDisconnect.get(5, TimeUnit.SECONDS);
                    writeFrame(output, disconnect);
                } catch (Throwable failure) { backendDone.completeExceptionally(failure); return; }
                backendDone.complete(null);
            }, "fake-disconnect-backend");
            backendThread.setDaemon(true);
            backendThread.start();

            var backend = catalog.register(new BackendRegistration(new BackendId("lobby"),
                    new BackendOwner("static", 0),
                    URI.create("tcp://127.0.0.1:" + backendServer.getLocalPort()), Map.of(), Map.of()));
            var listener = new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), catalog);
            listener.setPlacement(player -> CompletableFuture.completedFuture(Optional.of(PlacementDecision.select("lobby"))));
            var heldContext = new AtomicReference<ChannelHandlerContext>();
            var heldMessage = new AtomicReference<Object>();
            var heldPromise = new AtomicReference<ChannelPromise>();
            var heldWrite = new CompletableFuture<Void>();
            try {
                int port = ((InetSocketAddress) listener.start().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).localAddress()).getPort();
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                    client.setSoTimeout(5000);
                    sendLogin(client, username);
                    var input = new DataInputStream(client.getInputStream());
                    var output = new DataOutputStream(client.getOutputStream());
                    assertEquals(2, readVarInt(readFrame(input)));
                    writeFrame(output, new byte[]{0x03, 0x22});
                    assertArrayEquals(new byte[]{0x03, 0x44}, readFrame(input));

                    Session session = listener.allSessions().iterator().next();
                    var frontendField = Session.class.getDeclaredField("frontend");
                    frontendField.setAccessible(true);
                    Channel frontend = (Channel) frontendField.get(session);
                    var backendField = Session.class.getDeclaredField("backend");
                    backendField.setAccessible(true);
                    Channel backendChannel = (Channel) backendField.get(session);
                    frontend.eventLoop().submit(() -> frontend.pipeline().addFirst("hold-disconnect-write",
                            new ChannelOutboundHandlerAdapter() {
                                @Override public void write(ChannelHandlerContext ctx, Object message,
                                                            ChannelPromise promise) {
                                    heldContext.set(ctx);
                                    heldMessage.set(message);
                                    heldPromise.set(promise);
                                    heldWrite.complete(null);
                                }
                            })).get(5, TimeUnit.SECONDS);

                    sendDisconnect.complete(null);
                    heldWrite.get(5, TimeUnit.SECONDS);
                    backendDone.get(5, TimeUnit.SECONDS);
                    var relayField = Session.class.getDeclaredField("relay");
                    relayField.setAccessible(true);
                    var relay = (dev.strataproxy.core.relay.RawRelay.Link) relayField.get(session);
                    assertTrue(relay.hasPendingClientboundWrites());
                    backendChannel.eventLoop().submit(() -> backendChannel.pipeline()
                            .fireExceptionCaught(new IOException("backend read failed"))).get(5, TimeUnit.SECONDS);
                    assertTrue(backendChannel.closeFuture().await(5, TimeUnit.SECONDS));
                    backendChannel.eventLoop().submit(() -> { }).get(5, TimeUnit.SECONDS);
                    assertTrue(frontend.isOpen(), () -> "backend failure dropped an accepted disconnect frame; "
                            + "pending=" + relay.hasPendingClientboundWrites()
                            + " backend=" + backendChannel.pipeline().names()
                            + " frontend=" + frontend.pipeline().names());
                    if (completeClientWrite) {
                        frontend.eventLoop().submit(() -> heldContext.get().writeAndFlush(
                                heldMessage.getAndSet(null), heldPromise.get())).get(5, TimeUnit.SECONDS);
                        assertArrayEquals(disconnect, readFrame(input));
                    } else {
                        assertTrue(frontend.closeFuture().await(7, TimeUnit.SECONDS),
                                "the stalled client write must not keep the session open forever");
                    }
                    assertEquals(-1, input.read());
                    long cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!listener.allSessions().isEmpty()
                            && System.nanoTime() < cleanupDeadline) Thread.sleep(5);
                    assertTrue(listener.allSessions().isEmpty());
                }
            } finally {
                sendDisconnect.complete(null);
                ReferenceCountUtil.release(heldMessage.getAndSet(null));
                listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
                backendDone.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void fragmentsLoginRelaysOrdinaryFramesAndClosesSessionOnBackendClose() throws Exception {
        String username = "ForgePlayer";
        byte[] largePlay = new byte[4097];
        largePlay[0] = 0x03;
        java.util.Arrays.fill(largePlay, 1, largePlay.length, (byte) 0x5a);
        UUID offlineId = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
        var catalog = new InMemoryBackendCatalog();
        try (ServerSocket backendServer = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            var backendDone = new CompletableFuture<Void>();
            var backendThread = new Thread(() -> {
                try (Socket socket = backendServer.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    byte[] handshake = readFrame(input);
                    var decodedHandshake = new java.io.ByteArrayInputStream(handshake);
                    assertEquals(0, readVarInt(decodedHandshake));
                    assertEquals(5, readVarInt(decodedHandshake));
                    readString(decodedHandshake, 1024);
                    assertEquals(backendServer.getLocalPort(), (decodedHandshake.read() << 8) | decodedHandshake.read());
                    assertEquals(2, readVarInt(decodedHandshake));
                    byte[] loginStart = readFrame(input);
                    var decodedLoginStart = new java.io.ByteArrayInputStream(loginStart);
                    assertEquals(0, readVarInt(decodedLoginStart));
                    assertEquals(username, readString(decodedLoginStart, 64));

                    ByteArrayOutputStream loginSuccess = new ByteArrayOutputStream();
                    writeVarInt(loginSuccess, 2);
                    writeString(loginSuccess, offlineId.toString());
                    writeString(loginSuccess, username);
                    output.write(framed(loginSuccess.toByteArray()));
                    output.write(framed(new byte[] {0x02, 0x55, 0x66}));
                    output.flush();

                    byte[] c2s = readFrame(input);
                    assertArrayEquals(new byte[] {0x01, 0x22, 0x33, 0x44}, c2s);
                    byte[] s2c = new byte[] {0x03, 0x77, 0x66};
                    writeFrame(output, s2c);
                    assertArrayEquals(largePlay, readFrame(input));
                    writeFrame(output, largePlay);
                    backendDone.complete(null);
                } catch (Throwable failure) {
                    backendDone.completeExceptionally(failure);
                }
            }, "fake-strataproxy-backend");
            backendThread.setDaemon(true);
            backendThread.start();

            var backend = catalog.register(new BackendRegistration(new BackendId("lobby"), new BackendOwner("static", 0),
                    URI.create("tcp://127.0.0.1:" + backendServer.getLocalPort()), Map.of(), Map.of()));
            var listener = new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), catalog);
            listener.setPlacement(player -> CompletableFuture.completedFuture(Optional.of(PlacementDecision.select("lobby"))));
            try {
                var bound = listener.start().toCompletableFuture().get(5, TimeUnit.SECONDS);
                int listenPort = ((InetSocketAddress) bound.localAddress()).getPort();
                var emptyStatus = requestStatus(listenPort);
                assertEquals(0, emptyStatus.path("players").path("online").asInt());
                assertEquals(1, emptyStatus.path("players").path("max").asInt());
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(), listenPort)) {
                    client.setSoTimeout(5000);
                    DataOutputStream output = new DataOutputStream(client.getOutputStream());
                    DataInputStream input = new DataInputStream(client.getInputStream());
                    ByteArrayOutputStream handshake = new ByteArrayOutputStream();
                    writeVarInt(handshake, 0);
                    writeVarInt(handshake, 5);
                    writeString(handshake, "localhost");
                    handshake.write((backendServer.getLocalPort() >>> 8) & 0xff);
                    handshake.write(backendServer.getLocalPort() & 0xff);
                    writeVarInt(handshake, 2);
                    writeFrame(output, handshake.toByteArray());

                    ByteArrayOutputStream login = new ByteArrayOutputStream();
                    writeVarInt(login, 0);
                    writeString(login, username);
                    byte[] loginFrame = framed(login.toByteArray());
                    for (byte fragment : loginFrame) {
                        output.writeByte(fragment);
                        output.flush();
                        Thread.sleep(1);
                    }
                    byte[] successFrame = readFrame(input);
                    assertEquals(2, readVarInt(successFrame));
                    assertArrayEquals(new byte[] {0x02, 0x55, 0x66}, readFrame(input));

                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (listener.online().isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
                    assertEquals(1, listener.online().size());
                    assertEquals("lobby", listener.online().get(0).currentServer().orElseThrow());
                    var occupiedStatus = requestStatus(listenPort);
                    assertEquals(1, occupiedStatus.path("players").path("online").asInt());
                    assertEquals(1, occupiedStatus.path("players").path("max").asInt());

                    writeFrame(output, new byte[] {0x01, 0x22, 0x33, 0x44});
                    output.flush();
                    assertArrayEquals(new byte[] {0x03, 0x77, 0x66}, readFrame(input));
                    writeFrame(output, largePlay);
                    assertArrayEquals(largePlay, readFrame(input));
                    backendDone.get(5, TimeUnit.SECONDS);
                    assertEquals(-1, input.read());
                }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!listener.online().isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
                assertTrue(listener.online().isEmpty());
                while (listener.onlineCount() != 0 && System.nanoTime() < deadline) Thread.sleep(5);
                assertEquals(0, requestStatus(listenPort).path("players").path("online").asInt());
            } finally {
                listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void backendClosingBeforeLoginSuccessSendsReasonAndReleasesReservation() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        try (ServerSocket backendServer = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            Thread backendThread = new Thread(() -> {
                try (Socket socket = backendServer.accept()) {
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    readFrame(input);
                    readFrame(input);
                } catch (Exception ignored) { }
            }, "fake-closing-backend");
            backendThread.setDaemon(true);
            backendThread.start();
            var backend = catalog.register(new BackendRegistration(new BackendId("lobby"), new BackendOwner("static", 0),
                    URI.create("tcp://127.0.0.1:" + backendServer.getLocalPort()), Map.of(), Map.of()));
            var listener = new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), catalog);
            listener.setPlacement(player -> CompletableFuture.completedFuture(Optional.of(PlacementDecision.select("lobby"))));
            try {
                var bound = listener.start().toCompletableFuture().get(5, TimeUnit.SECONDS);
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(), ((InetSocketAddress) bound.localAddress()).getPort())) {
                    client.setSoTimeout(5000);
                    DataOutputStream output = new DataOutputStream(client.getOutputStream());
                    DataInputStream input = new DataInputStream(client.getInputStream());
                    ByteArrayOutputStream handshake = new ByteArrayOutputStream();
                    writeVarInt(handshake, 0); writeVarInt(handshake, 5); writeString(handshake, "localhost");
                    handshake.write(0); handshake.write(1); writeVarInt(handshake, 2);
                    writeFrame(output, handshake.toByteArray());
                    ByteArrayOutputStream login = new ByteArrayOutputStream();
                    writeVarInt(login, 0); writeString(login, "CloseMe");
                    writeFrame(output, login.toByteArray());
                    var packet = new java.io.ByteArrayInputStream(readFrame(input));
                    assertEquals(0, readVarInt(packet));
                    String component = readString(packet, 32767);
                    assertEquals("Selected server closed during login.",
                            new ObjectMapper().readTree(component).path("text").asText());
                    assertEquals(0, packet.available());
                    assertEquals(-1, input.read());
                }
                assertTrue(listener.online().isEmpty());
                long cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!listener.allSessions().isEmpty()
                        && System.nanoTime() < cleanupDeadline) Thread.sleep(5);
                assertTrue(listener.allSessions().isEmpty());
            } finally {
                listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void clientDisconnectWhileBackendLoginIsPendingReleasesReservation() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        try (ServerSocket backendServer = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            var backendReceivedLogin = new CompletableFuture<Void>();
            var backendClosed = new CompletableFuture<Void>();
            Thread backendThread = new Thread(() -> {
                try (Socket socket = backendServer.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    readFrame(input);
                    readFrame(input);
                    backendReceivedLogin.complete(null);
                    assertEquals(-1, input.read());
                    backendClosed.complete(null);
                } catch (Throwable failure) {
                    backendReceivedLogin.completeExceptionally(failure);
                    backendClosed.completeExceptionally(failure);
                }
            }, "fake-pending-login-backend");
            backendThread.setDaemon(true);
            backendThread.start();
            var backend = catalog.register(new BackendRegistration(new BackendId("lobby"),
                    new BackendOwner("static", 0), URI.create("tcp://127.0.0.1:" + backendServer.getLocalPort()), Map.of(), Map.of()));
            var listener = new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), catalog);
            listener.setPlacement(player -> CompletableFuture.completedFuture(
                    Optional.of(PlacementDecision.select("lobby"))));
            try {
                int port = ((InetSocketAddress) listener.start().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).localAddress()).getPort();
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                    sendLogin(client, "LeftAtLogin");
                    backendReceivedLogin.get(5, TimeUnit.SECONDS);
                }
                backendClosed.get(5, TimeUnit.SECONDS);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!listener.allSessions().isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
                assertTrue(listener.allSessions().isEmpty());
            } finally {
                listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode requestStatus(int port) throws Exception {
        try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
            client.setSoTimeout(5000);
            DataOutputStream output = new DataOutputStream(client.getOutputStream());
            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            writeVarInt(handshake, 0);
            writeVarInt(handshake, 5);
            writeString(handshake, "localhost");
            handshake.write((port >>> 8) & 0xff);
            handshake.write(port & 0xff);
            writeVarInt(handshake, 1);
            writeFrame(output, handshake.toByteArray());
            writeFrame(output, new byte[]{0});
            var response = new java.io.ByteArrayInputStream(readFrame(new DataInputStream(client.getInputStream())));
            assertEquals(0, readVarInt(response));
            return new ObjectMapper().readTree(readString(response, 32767));
        }
    }

    private static byte[] readFrame(DataInputStream input) throws Exception {
        int length = readVarInt(input);
        byte[] frame = input.readNBytes(length);
        if (frame.length != length) throw new java.io.EOFException();
        return frame;
    }

    private static byte[] framed(byte[] payload) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeVarInt(output, payload.length);
        output.write(payload);
        return output.toByteArray();
    }

    private static void writeFrame(DataOutputStream output, byte[] payload) throws Exception {
        output.write(framed(payload));
        output.flush();
    }

    private static int readVarInt(byte[] bytes) {
        return readVarInt(new java.io.ByteArrayInputStream(bytes));
    }

    private static int readVarInt(java.io.InputStream input) {
        try {
            int value = 0;
            int shift = 0;
            int current;
            do {
                current = input.read();
                if (current < 0 || shift >= 35) throw new java.io.EOFException();
                value |= (current & 0x7f) << shift;
                shift += 7;
            } while ((current & 0x80) != 0);
            return value;
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }

    private static int readVarInt(DataInputStream input) throws Exception { return readVarInt((java.io.InputStream) input); }

    private static String readString(java.io.InputStream input, int maxLength) throws Exception {
        int length = readVarInt(input);
        if (length < 0 || length > maxLength * 4) throw new IllegalArgumentException("bad test string");
        return new String(input.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static void writeString(ByteArrayOutputStream output, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length);
        output.write(bytes);
    }

    private static void writeVarInt(ByteArrayOutputStream output, int value) {
        do {
            int part = value & 0x7f;
            value >>>= 7;
            output.write(value == 0 ? part : part | 0x80);
        } while (value != 0);
    }
}

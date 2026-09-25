package dev.strataproxy.core.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.strataproxy.api.PlacementDecision;
import dev.strataproxy.core.auth.MinecraftEncryptionRequest;
import dev.strataproxy.core.auth.MinecraftEncryptionResponse;
import dev.strataproxy.core.auth.VerifiedProfile;
import dev.strataproxy.core.backend.BackendId;
import dev.strataproxy.core.backend.BackendOwner;
import dev.strataproxy.core.backend.BackendRegistration;
import dev.strataproxy.core.backend.InMemoryBackendCatalog;
import org.junit.jupiter.api.Test;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProxySessionListenerTest {
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
                    URI.create("tcp://127.0.0.1:" + backendServer.getLocalPort()), 1, Map.of(), Map.of()));
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
    void closingListenerEndsPendingPlacementAndIgnoresLateDecision() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        var registered = catalog.register(new BackendRegistration(new BackendId("lobby"),
                new BackendOwner("static", 0), URI.create("tcp://127.0.0.1:1"), 1, Map.of(), Map.of()));
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
                assertEquals(1, catalog.find(registered.handle().id()).orElseThrow().availableUnits());
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
            decision.complete(Optional.of(PlacementDecision.reject("too late")));
            assertEquals(0, listener.onlineCount());
        } finally {
            listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void closingListenerDisconnectsActiveSessionAndReleasesCapacity() throws Exception {
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
                    URI.create("tcp://127.0.0.1:" + backendServer.getLocalPort()), 1, Map.of(), Map.of()));
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
                    assertEquals(1, catalog.find(registered.handle().id()).orElseThrow().connectedPlayers());

                    listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
                    assertEquals(-1, client.getInputStream().read());
                    backendClosed.get(5, TimeUnit.SECONDS);
                    assertTrue(listener.online().isEmpty());
                    assertTrue(listener.allSessions().isEmpty());
                    assertEquals(0, listener.onlineCount());
                    assertEquals(1, catalog.find(registered.handle().id()).orElseThrow().availableUnits());
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
                    URI.create("tcp://backend.test:" + backendServer.getLocalPort()), 1, Map.of(), Map.of()));
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
                    URI.create("tcp://127.0.0.1:" + backendServer.getLocalPort()), 1, Map.of(), Map.of()));
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
    void backendLoginStillHasADeadlineAfterPlacement() throws Exception {
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
                    URI.create("tcp://127.0.0.1:" + backendServer.getLocalPort()), 1, Map.of(), Map.of()));
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
                    assertEquals(-1, client.getInputStream().read());
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
    void stalledInitialPlayHandshakeClosesSessionAndReleasesCapacity() throws Exception {
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
                    URI.create("tcp://127.0.0.1:" + backendServer.getLocalPort()), 1, Map.of(), Map.of()));
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
                while ((!listener.online().isEmpty()
                        || catalog.find(registered.handle().id()).orElseThrow().availableUnits() != 1)
                        && System.nanoTime() < deadline) Thread.sleep(5);
                assertTrue(listener.online().isEmpty());
                assertEquals(1, catalog.find(registered.handle().id()).orElseThrow().availableUnits());
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
                    URI.create("tcp://127.0.0.1:" + backendServer.getLocalPort()), 1, Map.of(), Map.of()));
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
                    new BackendOwner("static", 0), URI.create("tcp://127.0.0.1:" + backendServer.getLocalPort()),
                    2, Map.of(), Map.of()));
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
                    assertEquals(1, catalog.find(backend.handle().id()).orElseThrow().connectedPlayers());
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
                    URI.create("tcp://127.0.0.1:" + backendServer.getLocalPort()), 1, Map.of(), Map.of()));
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
    void fragmentsLoginRelaysOrdinaryFramesAndReleasesCapacityOnBackendClose() throws Exception {
        String username = "ForgePlayer";
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
                    backendDone.complete(null);
                } catch (Throwable failure) {
                    backendDone.completeExceptionally(failure);
                }
            }, "fake-strataproxy-backend");
            backendThread.setDaemon(true);
            backendThread.start();

            var backend = catalog.register(new BackendRegistration(new BackendId("lobby"), new BackendOwner("static", 0),
                    URI.create("tcp://127.0.0.1:" + backendServer.getLocalPort()), 1, Map.of(), Map.of()));
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
                    assertEquals(0, catalog.find(backend.handle().id()).orElseThrow().availableUnits());
                    var occupiedStatus = requestStatus(listenPort);
                    assertEquals(1, occupiedStatus.path("players").path("online").asInt());
                    assertEquals(1, occupiedStatus.path("players").path("max").asInt());

                    writeFrame(output, new byte[] {0x01, 0x22, 0x33, 0x44});
                    output.flush();
                    assertArrayEquals(new byte[] {0x03, 0x77, 0x66}, readFrame(input));
                    backendDone.get(5, TimeUnit.SECONDS);
                    assertEquals(-1, input.read());
                }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!listener.online().isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
                assertTrue(listener.online().isEmpty());
                assertEquals(1, catalog.find(backend.handle().id()).orElseThrow().availableUnits());
                while (listener.onlineCount() != 0 && System.nanoTime() < deadline) Thread.sleep(5);
                assertEquals(0, requestStatus(listenPort).path("players").path("online").asInt());
            } finally {
                listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void backendClosingBeforeLoginSuccessClosesClientAndReleasesReservation() throws Exception {
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
                    URI.create("tcp://127.0.0.1:" + backendServer.getLocalPort()), 1, Map.of(), Map.of()));
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
                    assertEquals(-1, input.read());
                }
                assertTrue(listener.online().isEmpty());
                assertEquals(1, catalog.find(backend.handle().id()).orElseThrow().availableUnits());
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
                    new BackendOwner("static", 0), URI.create("tcp://127.0.0.1:" + backendServer.getLocalPort()),
                    1, Map.of(), Map.of()));
            var listener = new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), catalog);
            listener.setPlacement(player -> CompletableFuture.completedFuture(
                    Optional.of(PlacementDecision.select("lobby"))));
            try {
                int port = ((InetSocketAddress) listener.start().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).localAddress()).getPort();
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                    sendLogin(client, "LeftAtLogin");
                    backendReceivedLogin.get(5, TimeUnit.SECONDS);
                    assertEquals(0, catalog.find(backend.handle().id()).orElseThrow().availableUnits());
                }
                backendClosed.get(5, TimeUnit.SECONDS);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!listener.allSessions().isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
                assertTrue(listener.allSessions().isEmpty());
                assertEquals(1, catalog.find(backend.handle().id()).orElseThrow().availableUnits());
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

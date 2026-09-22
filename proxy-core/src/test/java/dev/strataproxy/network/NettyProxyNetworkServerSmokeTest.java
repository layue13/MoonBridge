package dev.strataproxy.network;

import dev.strataproxy.api.server.ProtocolRange;
import dev.strataproxy.api.server.RegisteredServer;
import dev.strataproxy.api.server.ServerCapability;
import dev.strataproxy.api.server.ServerDescriptor;
import dev.strataproxy.api.server.ServerHealth;
import dev.strataproxy.api.server.ServerLoad;
import dev.strataproxy.compression.CompressionStrategies;
import dev.strataproxy.network.MinecraftVarInts;
import dev.strataproxy.command.DefaultCommandRegistry;
import dev.strataproxy.command.SimpleEventBus;
import dev.strataproxy.plugin.command.CommandResult;
import dev.strataproxy.plugin.command.CommandSpec;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NettyProxyNetworkServerSmokeTest {
    @Test
    void cleansUpEventLoopsWhenBindFails() throws Exception {
        try (var first = new NettyProxyNetworkServer(
                1,
                (request, remoteAddress) -> java.util.Optional.empty(),
                new ProxyMetrics(),
                NetworkTuning.defaults())) {
            first.bind(new InetSocketAddress("127.0.0.1", 0))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            var contender = new NettyProxyNetworkServer(
                    1,
                    (request, remoteAddress) -> java.util.Optional.empty(),
                    new ProxyMetrics(),
                    NetworkTuning.defaults());

            assertThrows(ExecutionException.class, () -> contender.bind(first.bindAddress())
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS));
            assertTrue(contender.shuttingDown());
            contender.close();
        }
    }

    @Test
    void rejectsPerAddressConnectionStormOverRealTcp() throws Exception {
        var metrics = new ProxyMetrics();
        var tuning = new NetworkTuning(1024, 1_000, 128, 1024, 100, 2, 10_000);
        try (var proxy = new NettyProxyNetworkServer(
                1,
                (request, remoteAddress) -> java.util.Optional.empty(),
                metrics,
                tuning)) {
            proxy.bind(new InetSocketAddress("127.0.0.1", 0))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            var attempts = 12;
            var connectedSockets = Collections.synchronizedList(new ArrayList<Socket>());
            var start = new CountDownLatch(1);
            var connected = new CountDownLatch(attempts);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (var i = 0; i < attempts; i++) {
                    executor.submit(() -> {
                        start.await();
                        var socket = new Socket();
                        socket.connect(proxy.bindAddress(), 5_000);
                        connectedSockets.add(socket);
                        connected.countDown();
                        return null;
                    });
                }

                start.countDown();
                assertTrue(connected.await(5, TimeUnit.SECONDS));
                awaitConnectionStormMetrics(metrics, 2, attempts - 2);
            } finally {
                closeAll(connectedSockets);
            }

            awaitGlobalActiveConnections(metrics, 0);
        }
    }

    @Test
    void forwardsMinecraftHandshakeAndBackendBytesOverRealTcp() throws Exception {
        var handshake = handshakeFrame(763, "play.example.net", 25565, 2);
        var pendingLoginFrame = packetFrame(0x01, 42);
        var clientBytes = concat(handshake, pendingLoginFrame);
        var backendResponse = new byte[] {0x11, 0x22, 0x33, 0x44};
        var backendExecutor = Executors.newSingleThreadExecutor();

        try (var backendSocket = new ServerSocket(0)) {
            backendSocket.setSoTimeout(5_000);
            var backendPort = backendSocket.getLocalPort();
            var backendRead = backendExecutor.submit(() -> {
                try (var accepted = backendSocket.accept()) {
                    accepted.setSoTimeout(5_000);
                    var received = accepted.getInputStream().readNBytes(clientBytes.length);
                    accepted.getOutputStream().write(backendResponse);
                    accepted.getOutputStream().flush();
                    return received;
                }
            });

            var selected = server("survival-1", backendPort);
            var metrics = new ProxyMetrics();
            var tuning = new NetworkTuning(1024, 1_000, 128, 1024, 100, 100, 5_000);
            try (var proxy = new NettyProxyNetworkServer(
                    1,
                    (request, remoteAddress) -> java.util.Optional.of(selected),
                    metrics,
                    tuning)) {
                proxy.bind(new InetSocketAddress("127.0.0.1", 0))
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);
                assertEquals("nio", proxy.transportName());

                try (var client = new Socket()) {
                    client.connect(proxy.bindAddress(), 5_000);
                    client.setSoTimeout(5_000);
                    client.getOutputStream().write(clientBytes);
                    client.getOutputStream().flush();

                    assertArrayEquals(backendResponse, client.getInputStream().readNBytes(backendResponse.length));
                }

                assertArrayEquals(clientBytes, backendRead.get(5, TimeUnit.SECONDS));
                awaitMetrics(metrics, clientBytes.length, backendResponse.length);
                awaitServerActiveConnections(metrics, 0);
                awaitGlobalActiveConnections(metrics, 0);
                var snapshot = metrics.snapshot();
                var traffic = snapshot.serverTraffic().get("survival-1");
                var connections = snapshot.serverConnections().get("survival-1");
                assertEquals(1, snapshot.routedConnections());
                assertEquals(0, snapshot.activeConnections());
                assertEquals(clientBytes.length, snapshot.frontendToBackendBytes());
                assertEquals(backendResponse.length, snapshot.backendToFrontendBytes());
                assertEquals(1, connections.routedConnections());
                assertEquals(0, connections.activeConnections());
                assertEquals(clientBytes.length, traffic.frontendToBackendBytes());
                assertEquals(backendResponse.length, traffic.backendToFrontendBytes());
            }
        } finally {
            backendExecutor.shutdownNow();
        }
    }

    @Test
    void bungeeConnectPluginMessageReplacesBackendAfterNewConnectionIsReady() throws Exception {
        var handshake = handshakeFrame(763, "play.example.net", 25565, 2);
        var login = loginStartFrame("Steve");
        var clientBytes = concat(handshake, login);
        var afterReplacementBytes = packetFrame(0x02, 99);
        var staleOldBackendFrame = packetFrame(0x03, 123);
        var executor = Executors.newFixedThreadPool(2);

        try (var firstBackend = new ServerSocket(0);
             var secondBackend = new ServerSocket(0)) {
            firstBackend.setSoTimeout(5_000);
            secondBackend.setSoTimeout(5_000);
            var firstBackendInitialBytes = new java.util.concurrent.atomic.AtomicReference<byte[]>();
            var firstBackendClosed = executor.submit(() -> {
                try (var accepted = firstBackend.accept()) {
                    accepted.setSoTimeout(5_000);
                    var first = readMinecraftFrame(accepted.getInputStream());
                    var second = readMinecraftFrame(accepted.getInputStream());
                    firstBackendInitialBytes.set(concat(first, second));
                    accepted.getOutputStream().write(bungeeConnectFrame("survival-2"));
                    accepted.getOutputStream().write(staleOldBackendFrame);
                    accepted.getOutputStream().flush();
                    try {
                        while (accepted.getInputStream().read() >= 0) {
                            // The old backend should receive EOF after the replacement backend is connected.
                        }
                        return true;
                    } catch (SocketTimeoutException exception) {
                        return false;
                    }
                }
            });
            var secondBackendRead = executor.submit(() -> {
                try (var accepted = secondBackend.accept()) {
                    accepted.setSoTimeout(5_000);
                    var first = readMinecraftFrame(accepted.getInputStream());
                    var second = readMinecraftFrame(accepted.getInputStream());
                    accepted.getOutputStream().write(loginSuccessFrame());
                    accepted.getOutputStream().flush();
                    return new SwitchBackendRead(concat(first, second), accepted.getInputStream().readNBytes(afterReplacementBytes.length));
                }
            });

            var first = server("survival-1", firstBackend.getLocalPort());
            var second = server("survival-2", secondBackend.getLocalPort());
            var metrics = new ProxyMetrics();
            var tuning = new NetworkTuning(4096, 1_000, 128, 1024, 100, 100, 5_000);
            var resolver = new ReplacementBackendResolver(first, second);
            try (var proxy = new NettyProxyNetworkServer(
                    1,
                    resolver,
                    metrics,
                    tuning)) {
                proxy.bind(new InetSocketAddress("127.0.0.1", 0))
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);

                try (var client = new Socket()) {
                    client.connect(proxy.bindAddress(), 5_000);
                    client.setSoTimeout(5_000);
                    client.getOutputStream().write(clientBytes);
                    client.getOutputStream().flush();

                    awaitServerActiveConnections(metrics, "survival-2", 1);
                    client.setSoTimeout(250);
                    assertThrows(SocketTimeoutException.class, () -> readMinecraftFrame(client.getInputStream()));
                    client.setSoTimeout(5_000);
                    client.getOutputStream().write(afterReplacementBytes);
                    client.getOutputStream().flush();

                    var secondRead = secondBackendRead.get(5, TimeUnit.SECONDS);
                    assertArrayEquals(clientBytes, secondRead.loginBytes());
                    assertArrayEquals(afterReplacementBytes, secondRead.playBytes());
                    assertTrue(firstBackendClosed.get(5, TimeUnit.SECONDS));
                }
            }

            assertArrayEquals(clientBytes, firstBackendInitialBytes.get());
            var firstConnections = metrics.snapshot().serverConnections().get("survival-1");
            var secondConnections = metrics.snapshot().serverConnections().get("survival-2");
            assertEquals(0, firstConnections.activeConnections());
            assertEquals(1, secondConnections.routedConnections());
            assertEquals(1, metrics.snapshot().backendReplacements().get("attempted"));
            assertEquals(1, metrics.snapshot().backendReplacements().get("success"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void bungeeConnectBackendReplacementFailureResumesOldBackendRelay() throws Exception {
        var handshake = handshakeFrame(763, "play.example.net", 25565, 2);
        var login = loginStartFrame("Steve");
        var clientBytes = concat(handshake, login);
        var afterFailureBytes = packetFrame(0x02, 101);
        var backendExecutor = Executors.newSingleThreadExecutor();

        try (var firstBackend = new ServerSocket(0)) {
            firstBackend.setSoTimeout(5_000);
            var first = server("survival-1", firstBackend.getLocalPort());
            var unavailableReplacement = server("survival-2", freePort());
            var firstBackendReadAfterFailure = backendExecutor.submit(() -> {
                try (var accepted = firstBackend.accept()) {
                    accepted.setSoTimeout(5_000);
                    readMinecraftFrame(accepted.getInputStream());
                    readMinecraftFrame(accepted.getInputStream());
                    accepted.getOutputStream().write(bungeeConnectFrame("survival-2"));
                    accepted.getOutputStream().flush();
                    return accepted.getInputStream().readNBytes(afterFailureBytes.length);
                }
            });

            var metrics = new ProxyMetrics();
            var tuning = new NetworkTuning(4096, 250, 128, 1024, 100, 100, 5_000);
            var resolver = new ReplacementBackendResolver(first, unavailableReplacement);
            try (var proxy = new NettyProxyNetworkServer(
                    1,
                    resolver,
                    metrics,
                    tuning)) {
                proxy.bind(new InetSocketAddress("127.0.0.1", 0))
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);

                try (var client = new Socket()) {
                    client.connect(proxy.bindAddress(), 5_000);
                    client.setSoTimeout(5_000);
                    client.getOutputStream().write(clientBytes);
                    client.getOutputStream().flush();

                    awaitBackendConnectFailure(metrics);
                    client.setSoTimeout(250);
                    assertThrows(SocketTimeoutException.class, () -> readMinecraftFrame(client.getInputStream()));
                    client.setSoTimeout(5_000);
                    client.getOutputStream().write(afterFailureBytes);
                    client.getOutputStream().flush();

                    assertArrayEquals(afterFailureBytes, firstBackendReadAfterFailure.get(5, TimeUnit.SECONDS));
                }
            }
            assertEquals(1, metrics.snapshot().backendReplacements().get("attempted"));
            assertEquals(1, metrics.snapshot().backendReplacements().get("connect_failure"));
        } finally {
            backendExecutor.shutdownNow();
        }
    }

    @Test
    void externalPlayerTransferReplacesBackendAfterNewConnectionIsReady() throws Exception {
        var handshake = handshakeFrame(763, "play.example.net", 25565, 2);
        var login = loginStartFrame("Steve");
        var clientBytes = concat(handshake, login);
        var afterReplacementBytes = packetFrame(0x02, 111);
        var executor = Executors.newFixedThreadPool(2);

        try (var firstBackend = new ServerSocket(0);
             var secondBackend = new ServerSocket(0)) {
            firstBackend.setSoTimeout(5_000);
            secondBackend.setSoTimeout(5_000);
            var firstBackendClosed = executor.submit(() -> {
                try (var accepted = firstBackend.accept()) {
                    accepted.setSoTimeout(5_000);
                    readMinecraftFrame(accepted.getInputStream());
                    readMinecraftFrame(accepted.getInputStream());
                    try {
                        while (accepted.getInputStream().read() >= 0) {
                            // The old backend should receive EOF after the transfer backend is connected.
                        }
                        return true;
                    } catch (SocketTimeoutException exception) {
                        return false;
                    }
                }
            });
            var secondBackendRead = executor.submit(() -> {
                try (var accepted = secondBackend.accept()) {
                    accepted.setSoTimeout(5_000);
                    var first = readMinecraftFrame(accepted.getInputStream());
                    var second = readMinecraftFrame(accepted.getInputStream());
                    accepted.getOutputStream().write(loginSuccessFrame());
                    accepted.getOutputStream().flush();
                    return new SwitchBackendRead(concat(first, second), accepted.getInputStream().readNBytes(afterReplacementBytes.length));
                }
            });

            var first = server("survival-1", firstBackend.getLocalPort());
            var second = server("survival-2", secondBackend.getLocalPort());
            var metrics = new ProxyMetrics();
            var tuning = new NetworkTuning(4096, 1_000, 128, 1024, 100, 100, 5_000);
            var resolver = new ReplacementBackendResolver(first, second);
            try (var proxy = new NettyProxyNetworkServer(
                    1,
                    resolver,
                    metrics,
                    tuning)) {
                proxy.bind(new InetSocketAddress("127.0.0.1", 0))
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);

                try (var client = new Socket()) {
                    client.connect(proxy.bindAddress(), 5_000);
                    client.setSoTimeout(5_000);
                    client.getOutputStream().write(clientBytes);
                    client.getOutputStream().flush();

                    awaitPlayerSession(metrics, "Steve", "survival-1");
                    var transfer = proxy.transferPlayer("Steve", "survival-2")
                            .toCompletableFuture()
                            .get(5, TimeUnit.SECONDS);
                    assertTrue(transfer.success(), "transfer outcome was " + transfer.outcome());
                    assertEquals("survival-1", transfer.sourceServer());
                    assertEquals("survival-2", transfer.targetServer());

                    client.getOutputStream().write(afterReplacementBytes);
                    client.getOutputStream().flush();

                    var secondRead = secondBackendRead.get(5, TimeUnit.SECONDS);
                    assertArrayEquals(clientBytes, secondRead.loginBytes());
                    assertArrayEquals(afterReplacementBytes, secondRead.playBytes());
                    assertTrue(firstBackendClosed.get(5, TimeUnit.SECONDS));
                    assertEquals("survival-2", metrics.snapshot().playerSessions().get("Steve").server());
                }
            }

            var snapshot = metrics.snapshot();
            assertEquals(1, snapshot.backendReplacements().get("attempted"));
            assertEquals(1, snapshot.backendReplacements().get("success"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void gameCommandTransfersPlayerWithoutForwardingCommandToOldBackend() throws Exception {
        var handshake = handshakeFrame(763, "play.example.net", 25565, 2);
        var login = loginStartFrame("Steve");
        var clientBytes = concat(handshake, login);
        var commandFrame = playCommandFrame(0x04, "server survival-2");
        var afterReplacementBytes = packetFrame(0x02, 117);
        var executor = Executors.newFixedThreadPool(2);

        try (var firstBackend = new ServerSocket(0);
             var secondBackend = new ServerSocket(0)) {
            firstBackend.setSoTimeout(5_000);
            secondBackend.setSoTimeout(5_000);
            var firstBackendClosedWithoutCommand = executor.submit(() -> {
                try (var accepted = firstBackend.accept()) {
                    accepted.setSoTimeout(5_000);
                    readMinecraftFrame(accepted.getInputStream());
                    readMinecraftFrame(accepted.getInputStream());
                    return accepted.getInputStream().read() < 0;
                }
            });
            var secondBackendRead = executor.submit(() -> {
                try (var accepted = secondBackend.accept()) {
                    accepted.setSoTimeout(5_000);
                    var first = readMinecraftFrame(accepted.getInputStream());
                    var second = readMinecraftFrame(accepted.getInputStream());
                    accepted.getOutputStream().write(loginSuccessFrame());
                    accepted.getOutputStream().flush();
                    return new SwitchBackendRead(concat(first, second), accepted.getInputStream().readNBytes(afterReplacementBytes.length));
                }
            });

            var first = server("survival-1", firstBackend.getLocalPort());
            var second = server("survival-2", secondBackend.getLocalPort());
            var metrics = new ProxyMetrics();
            var tuning = new NetworkTuning(4096, 1_000, 128, 1024, 100, 100, 5_000);
            var resolver = new ReplacementBackendResolver(first, second);
            var proxyReference = new AtomicReference<NettyProxyNetworkServer>();
            var commands = new DefaultCommandRegistry();
            commands.register(new CommandSpec("server", List.of(), "", "", context ->
                    proxyReference.get().transferPlayer(context.source().name(), context.arguments().get(0))
                            .thenApply(result -> result.success()
                                    ? CommandResult.ok()
                                    : CommandResult.failure(result.outcome()))));
            try (var proxy = new NettyProxyNetworkServer(
                    1,
                    resolver,
                    metrics,
                    tuning,
                    commands,
                    new SimpleEventBus())) {
                proxyReference.set(proxy);
                proxy.bind(new InetSocketAddress("127.0.0.1", 0))
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);

                try (var client = new Socket()) {
                    client.connect(proxy.bindAddress(), 5_000);
                    client.setSoTimeout(5_000);
                    client.getOutputStream().write(clientBytes);
                    client.getOutputStream().flush();
                    awaitPlayerSession(metrics, "Steve", "survival-1");

                    client.getOutputStream().write(commandFrame);
                    client.getOutputStream().flush();
                    awaitServerActiveConnections(metrics, "survival-2", 1);

                    client.getOutputStream().write(afterReplacementBytes);
                    client.getOutputStream().flush();

                    var secondRead = secondBackendRead.get(5, TimeUnit.SECONDS);
                    assertArrayEquals(clientBytes, secondRead.loginBytes());
                    assertArrayEquals(afterReplacementBytes, secondRead.playBytes());
                    assertTrue(firstBackendClosedWithoutCommand.get(5, TimeUnit.SECONDS));
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void legacy1710GameCommandTransferSendsRespawnSwitchFrames() throws Exception {
        var handshake = handshakeFrame(5, "play.example.net", 25565, 2);
        var login = loginStartFrame("Steve");
        var clientBytes = concat(handshake, login);
        var commandFrame = playCommandFrame(0x01, "server survival-2");
        var afterReplacementBytes = packetFrame(0x03, 117);
        var executor = Executors.newFixedThreadPool(2);

        try (var firstBackend = new ServerSocket(0);
             var secondBackend = new ServerSocket(0)) {
            firstBackend.setSoTimeout(5_000);
            secondBackend.setSoTimeout(5_000);
            var firstBackendClosedWithoutCommand = executor.submit(() -> {
                try (var accepted = firstBackend.accept()) {
                    accepted.setSoTimeout(5_000);
                    readMinecraftFrame(accepted.getInputStream());
                    readMinecraftFrame(accepted.getInputStream());
                    return accepted.getInputStream().read() < 0;
                }
            });
            var secondBackendRead = executor.submit(() -> {
                try (var accepted = secondBackend.accept()) {
                    accepted.setSoTimeout(5_000);
                    var first = readMinecraftFrame(accepted.getInputStream());
                    var second = readMinecraftFrame(accepted.getInputStream());
                    accepted.getOutputStream().write(concat(loginSuccessFrame(), joinGame1710Frame(12, 0, 0, 2, "default")));
                    accepted.getOutputStream().flush();
                    return new SwitchBackendRead(concat(first, second), accepted.getInputStream().readNBytes(afterReplacementBytes.length));
                }
            });

            var first = server("survival-1", firstBackend.getLocalPort());
            var second = server("survival-2", secondBackend.getLocalPort());
            var metrics = new ProxyMetrics();
            var tuning = new NetworkTuning(4096, 1_000, 128, 1024, 100, 100, 5_000);
            var resolver = new ReplacementBackendResolver(first, second);
            var proxyReference = new AtomicReference<NettyProxyNetworkServer>();
            var commands = new DefaultCommandRegistry();
            commands.register(new CommandSpec("server", List.of(), "", "", context ->
                    proxyReference.get().transferPlayer(context.source().name(), context.arguments().get(0))
                            .thenApply(result -> result.success()
                                    ? CommandResult.ok()
                                    : CommandResult.failure(result.outcome()))));
            try (var proxy = new NettyProxyNetworkServer(
                    1,
                    resolver,
                    metrics,
                    tuning,
                    commands,
                    new SimpleEventBus())) {
                proxyReference.set(proxy);
                proxy.bind(new InetSocketAddress("127.0.0.1", 0))
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);

                try (var client = new Socket()) {
                    client.connect(proxy.bindAddress(), 5_000);
                    client.setSoTimeout(5_000);
                    client.getOutputStream().write(clientBytes);
                    client.getOutputStream().flush();
                    awaitPlayerSession(metrics, "Steve", "survival-1");

                    client.getOutputStream().write(commandFrame);
                    client.getOutputStream().flush();
                    awaitServerActiveConnections(metrics, "survival-2", 1);

                    try {
                        assertEquals(0x07, packetId(readMinecraftFrame(client.getInputStream())));
                        assertEquals(0x07, packetId(readMinecraftFrame(client.getInputStream())));
                    } catch (java.io.IOException exception) {
                        var backendRead = secondBackendRead.isDone() ? secondBackendRead.get(5, TimeUnit.SECONDS) : null;
                        throw new AssertionError(
                                "secondBackendDone=" + secondBackendRead.isDone()
                                        + " secondPlayBytes="
                                        + (backendRead == null ? "pending" : backendRead.playBytes().length)
                                        + " snapshot=" + metrics.snapshot(),
                                exception);
                    }

                    client.getOutputStream().write(afterReplacementBytes);
                    client.getOutputStream().flush();

                    var secondRead = secondBackendRead.get(5, TimeUnit.SECONDS);
                    assertArrayEquals(clientBytes, secondRead.loginBytes());
                    assertArrayEquals(afterReplacementBytes, secondRead.playBytes());
                    assertTrue(firstBackendClosedWithoutCommand.get(5, TimeUnit.SECONDS));
                    assertEquals("survival-2", metrics.snapshot().playerSessions().get("Steve").server());
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void gameCommandResultMessageIsSentToClientAndNotForwarded() throws Exception {
        var handshake = handshakeFrame(763, "play.example.net", 25565, 2);
        var login = loginStartFrame("Steve");
        var clientBytes = concat(handshake, login);
        var commandFrame = playCommandFrame(0x04, "servers");
        var backendExecutor = Executors.newSingleThreadExecutor();

        try (var backendSocket = new ServerSocket(0)) {
            backendSocket.setSoTimeout(5_000);
            var backendPort = backendSocket.getLocalPort();
            var noCommandForwarded = backendExecutor.submit(() -> {
                try (var accepted = backendSocket.accept()) {
                    accepted.setSoTimeout(5_000);
                    readMinecraftFrame(accepted.getInputStream());
                    readMinecraftFrame(accepted.getInputStream());
                    accepted.setSoTimeout(300);
                    try {
                        return accepted.getInputStream().read() < 0;
                    } catch (SocketTimeoutException exception) {
                        return true;
                    }
                }
            });

            var selected = server("survival-1", backendPort);
            var metrics = new ProxyMetrics();
            var tuning = new NetworkTuning(4096, 1_000, 128, 1024, 100, 100, 5_000);
            var commands = new DefaultCommandRegistry();
            commands.register(new CommandSpec("servers", List.of(), "", "", context ->
                    java.util.concurrent.CompletableFuture.completedFuture(CommandResult.ok("Servers: survival-1"))));
            try (var proxy = new NettyProxyNetworkServer(
                    1,
                    (request, remoteAddress) -> java.util.Optional.of(selected),
                    metrics,
                    tuning,
                    commands,
                    new SimpleEventBus())) {
                proxy.bind(new InetSocketAddress("127.0.0.1", 0))
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);

                try (var client = new Socket()) {
                    client.connect(proxy.bindAddress(), 5_000);
                    client.setSoTimeout(5_000);
                    client.getOutputStream().write(clientBytes);
                    client.getOutputStream().flush();
                    awaitPlayerSession(metrics, "Steve", "survival-1");

                    client.getOutputStream().write(commandFrame);
                    client.getOutputStream().flush();

                    assertEquals("Servers: survival-1", systemChatMessage(readMinecraftFrame(client.getInputStream())));
                    assertTrue(noCommandForwarded.get(5, TimeUnit.SECONDS));
                }
            }
        } finally {
            backendExecutor.shutdownNow();
        }
    }

    @Test
    void externalPlayerTransferReportsMissingPlayer() throws Exception {
        var metrics = new ProxyMetrics();
        var tuning = new NetworkTuning(4096, 1_000, 128, 1024, 100, 100, 5_000);
        try (var proxy = new NettyProxyNetworkServer(
                1,
                (request, remoteAddress) -> java.util.Optional.empty(),
                metrics,
                tuning)) {
            proxy.bind(new InetSocketAddress("127.0.0.1", 0))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            var transfer = proxy.transferPlayer("MissingPlayer", "survival-2")
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertEquals("player_not_found", transfer.outcome());
            assertEquals("MissingPlayer", transfer.player());
            assertEquals("survival-2", transfer.targetServer());
        }
    }

    @Test
    void acceptsProxyProtocolV1AndRoutesWithForwardedAddressOverRealTcp() throws Exception {
        var handshake = handshakeFrame(763, "play.example.net", 25565, 2);
        var backendExecutor = Executors.newSingleThreadExecutor();
        var resolverAddress = new java.util.concurrent.atomic.AtomicReference<java.net.SocketAddress>();

        try (var backendSocket = new ServerSocket(0)) {
            backendSocket.setSoTimeout(5_000);
            var backendPort = backendSocket.getLocalPort();
            var backendRead = backendExecutor.submit(() -> {
                try (var accepted = backendSocket.accept()) {
                    accepted.setSoTimeout(5_000);
                    return readMinecraftFrame(accepted.getInputStream());
                }
            });

            var selected = server("survival-1", backendPort);
            var metrics = new ProxyMetrics();
            var tuning = new NetworkTuning(1024, 1_000, 128, 1024, 100, 100, 5_000, true);
            try (var proxy = new NettyProxyNetworkServer(
                    1,
                    (request, remoteAddress) -> {
                        resolverAddress.set(remoteAddress);
                        return java.util.Optional.of(selected);
                    },
                    metrics,
                    tuning)) {
                proxy.bind(new InetSocketAddress("127.0.0.1", 0))
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);

                try (var client = new Socket()) {
                    client.connect(proxy.bindAddress(), 5_000);
                    client.setSoTimeout(5_000);
                    client.getOutputStream().write("PROXY TCP4 203.0.113.7 198.51.100.10 41000 25577\r\n".getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().write(handshake);
                    client.getOutputStream().flush();
                }

                assertArrayEquals(handshake, backendRead.get(5, TimeUnit.SECONDS));
                assertEquals("203.0.113.7", ((InetSocketAddress) resolverAddress.get()).getHostString());
                assertEquals(41000, ((InetSocketAddress) resolverAddress.get()).getPort());
            }
        } finally {
            backendExecutor.shutdownNow();
        }
    }

    @Test
    void rewritesSplitLoginHandshakeForBungeeLegacyForwardingOverRealTcp() throws Exception {
        var handshake = handshakeFrame(763, "play.example.net", 25565, 2);
        var loginStart = loginStartFrame("OfflineName");
        var backendResponse = new byte[] {0x55, 0x66};
        var backendExecutor = Executors.newSingleThreadExecutor();

        try (var backendSocket = new ServerSocket(0)) {
            backendSocket.setSoTimeout(5_000);
            var backendPort = backendSocket.getLocalPort();
            var backendRead = backendExecutor.submit(() -> {
                try (var accepted = backendSocket.accept()) {
                    accepted.setSoTimeout(5_000);
                    var rewrittenHandshake = readMinecraftFrame(accepted.getInputStream());
                    var forwardedLoginStart = readMinecraftFrame(accepted.getInputStream());
                    accepted.getOutputStream().write(backendResponse);
                    accepted.getOutputStream().flush();
                    return List.of(rewrittenHandshake, forwardedLoginStart);
                }
            });

            var selected = server("survival-1", backendPort);
            var metrics = new ProxyMetrics();
            var tuning = new NetworkTuning(2048, 1_000, 128, 1024, 100, 100, 5_000);
            try (var proxy = new NettyProxyNetworkServer(
                    1,
                    (request, remoteAddress) -> java.util.Optional.of(selected),
                    metrics,
                    tuning,
                    false,
                    CompressionStrategies.from("adaptive"),
                    256,
                    8192,
                    0.75d,
                    null,
                    MinecraftAuthRuntime.offline(),
                    new MinecraftForwardingRuntime("bungee-legacy", ""),
                    false,
                    25)) {
                proxy.bind(new InetSocketAddress("127.0.0.1", 0))
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);

                try (var client = new Socket()) {
                    client.connect(proxy.bindAddress(), 5_000);
                    client.setSoTimeout(5_000);
                    client.getOutputStream().write(handshake);
                    client.getOutputStream().flush();
                    client.getOutputStream().write(loginStart);
                    client.getOutputStream().flush();

                    assertArrayEquals(backendResponse, client.getInputStream().readNBytes(backendResponse.length));
                }

                var frames = backendRead.get(5, TimeUnit.SECONDS);
                var fields = handshakeHost(frames.get(0)).split("\0", -1);
                assertEquals(4, fields.length);
                assertEquals("play.example.net", fields[0]);
                assertEquals("127.0.0.1", fields[1]);
                assertEquals(offlineUuidNoDashes("OfflineName"), fields[2]);
                assertEquals("[]", fields[3]);
                assertArrayEquals(loginStart, frames.get(1));
            }
        } finally {
            backendExecutor.shutdownNow();
        }
    }

    @Test
    void sendsLoginDisconnectWhenBackendConnectFailsOverRealTcp() throws Exception {
        var closedBackendPort = freePort();
        var selected = server("survival-1", closedBackendPort);
        var metrics = new ProxyMetrics();
        var tuning = new NetworkTuning(2048, 500, 128, 1024, 100, 100, 5_000);
        try (var proxy = new NettyProxyNetworkServer(
                1,
                (request, remoteAddress) -> java.util.Optional.of(selected),
                metrics,
                tuning)) {
            proxy.bind(new InetSocketAddress("127.0.0.1", 0))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            try (var client = new Socket()) {
                client.connect(proxy.bindAddress(), 5_000);
                client.setSoTimeout(5_000);
                client.getOutputStream().write(handshakeFrame(763, "play.example.net", 25565, 2));
                client.getOutputStream().flush();

                assertEquals("Backend server is unavailable.", loginDisconnectReason(readMinecraftFrame(client.getInputStream())));
            }

            awaitBackendConnectFailure(metrics);
        }
    }

    @Test
    void sendsLoginDisconnectWhenNoRouteExistsOverRealTcp() throws Exception {
        var metrics = new ProxyMetrics();
        var tuning = new NetworkTuning(2048, 500, 128, 1024, 100, 100, 5_000);
        try (var proxy = new NettyProxyNetworkServer(
                1,
                (request, remoteAddress) -> java.util.Optional.empty(),
                metrics,
                tuning)) {
            proxy.bind(new InetSocketAddress("127.0.0.1", 0))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            try (var client = new Socket()) {
                client.connect(proxy.bindAddress(), 5_000);
                client.setSoTimeout(5_000);
                client.getOutputStream().write(handshakeFrame(763, "missing.example.net", 25565, 2));
                client.getOutputStream().write(loginStartFrame("MissingRoute"));
                client.getOutputStream().flush();

                assertEquals("No available backend server for this route.", loginDisconnectReason(readMinecraftFrame(client.getInputStream())));
            }

            assertEquals(1, metrics.snapshot().failedRoutes());
        }
    }

    @Test
    void sendsLoginDisconnectWhenPendingLoginRequestIsTooLargeOverRealTcp() throws Exception {
        var selected = server("survival-1", freePort());
        var metrics = new ProxyMetrics();
        var tuning = new NetworkTuning(64, 500, 128, 1024, 100, 100, 5_000);
        try (var proxy = new NettyProxyNetworkServer(
                1,
                (request, remoteAddress) -> java.util.Optional.of(selected),
                metrics,
                tuning)) {
            proxy.bind(new InetSocketAddress("127.0.0.1", 0))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            try (var client = new Socket()) {
                client.connect(proxy.bindAddress(), 5_000);
                client.setSoTimeout(5_000);
                client.getOutputStream().write(concat(handshakeFrame(763, "play.example.net", 25565, 2), new byte[96]));
                client.getOutputStream().flush();

                assertEquals("Login request is too large.", loginDisconnectReason(readMinecraftFrame(client.getInputStream())));
            }

            assertEquals(1, metrics.snapshot().failedRoutes());
        }
    }

    private static void awaitMetrics(ProxyMetrics metrics, int frontendBytes, int backendBytes) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            var snapshot = metrics.snapshot();
            if (snapshot.frontendToBackendBytes() == frontendBytes
                    && snapshot.backendToFrontendBytes() == backendBytes
                    && snapshot.routedConnections() == 1) {
                return;
            }
            Thread.sleep(10);
        }
        var snapshot = metrics.snapshot();
        assertTrue(snapshot.routedConnections() == 1, "route count was " + snapshot.routedConnections());
        assertEquals(frontendBytes, snapshot.frontendToBackendBytes());
        assertEquals(backendBytes, snapshot.backendToFrontendBytes());
    }

    private static void awaitGlobalActiveConnections(ProxyMetrics metrics, long activeConnections) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (metrics.snapshot().activeConnections() == activeConnections) {
                return;
            }
            Thread.sleep(10);
        }
        assertEquals(activeConnections, metrics.snapshot().activeConnections());
    }

    private static void awaitServerActiveConnections(ProxyMetrics metrics, long activeConnections) throws InterruptedException {
        awaitServerActiveConnections(metrics, "survival-1", activeConnections);
    }

    private static void awaitServerActiveConnections(ProxyMetrics metrics, String serverName, long activeConnections) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            var connections = metrics.snapshot().serverConnections().get(serverName);
            if (connections != null && connections.activeConnections() == activeConnections) {
                return;
            }
            Thread.sleep(10);
        }
        var connections = metrics.snapshot().serverConnections().get(serverName);
        assertEquals(activeConnections, connections == null ? 0 : connections.activeConnections());
    }

    private static void awaitPlayerSession(ProxyMetrics metrics, String playerName, String serverName) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            var session = metrics.snapshot().playerSessions().get(playerName);
            if (session != null && session.server().equals(serverName)) {
                return;
            }
            Thread.sleep(10);
        }
        var session = metrics.snapshot().playerSessions().get(playerName);
        assertEquals(serverName, session == null ? "" : session.server());
    }

    private static void awaitConnectionStormMetrics(
            ProxyMetrics metrics,
            long acceptedConnections,
            long rejectedConnections) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            var snapshot = metrics.snapshot();
            if (snapshot.acceptedConnections() == acceptedConnections
                    && snapshot.activeConnections() == acceptedConnections
                    && snapshot.rejectedConnections() == rejectedConnections) {
                return;
            }
            Thread.sleep(10);
        }
        var snapshot = metrics.snapshot();
        assertEquals(acceptedConnections, snapshot.acceptedConnections());
        assertEquals(acceptedConnections, snapshot.activeConnections());
        assertEquals(rejectedConnections, snapshot.rejectedConnections());
    }

    private static void awaitBackendConnectFailure(ProxyMetrics metrics) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (metrics.snapshot().backendConnectFailures() == 1) {
                return;
            }
            Thread.sleep(10);
        }
        assertEquals(1, metrics.snapshot().backendConnectFailures());
    }

    private static void closeAll(List<Socket> sockets) {
        for (var socket : sockets) {
            try {
                socket.close();
            } catch (java.io.IOException ignored) {
                // Best effort cleanup for sockets that were already rejected by the proxy.
            }
        }
    }

    private static RegisteredServer server(String name, int port) {
        var descriptor = new ServerDescriptor(
                name,
                new InetSocketAddress("127.0.0.1", port),
                Set.of("survival"),
                Set.of(ServerCapability.LARGE_PAYLOAD),
                new ProtocolRange(0, Integer.MAX_VALUE, "any"),
                100,
                100,
                120,
                false,
                Map.of("host", "play.example.net"));
        return new TestRegisteredServer(
                descriptor,
                ServerHealth.up(1),
                new ServerLoad(0, 100, 120, 0, 0, 0, 0),
                false);
    }

    private static byte[] handshakeFrame(int protocol, String host, int port, int nextState) {
        var payload = new ByteArrayOutputStream();
        writeVarInt(payload, 0);
        writeVarInt(payload, protocol);
        writeString(payload, host);
        payload.write((port >>> 8) & 0xFF);
        payload.write(port & 0xFF);
        writeVarInt(payload, nextState);

        var payloadBytes = payload.toByteArray();
        var frame = new ByteArrayOutputStream();
        writeVarInt(frame, payloadBytes.length);
        frame.writeBytes(payloadBytes);
        return frame.toByteArray();
    }

    private static byte[] packetFrame(int packetId, int value) {
        var payload = new ByteArrayOutputStream();
        writeVarInt(payload, packetId);
        writeVarInt(payload, value);

        var payloadBytes = payload.toByteArray();
        var frame = new ByteArrayOutputStream();
        writeVarInt(frame, payloadBytes.length);
        frame.writeBytes(payloadBytes);
        return frame.toByteArray();
    }

    private static byte[] loginStartFrame(String username) {
        var payload = new ByteArrayOutputStream();
        writeVarInt(payload, 0);
        writeString(payload, username);

        var payloadBytes = payload.toByteArray();
        var frame = new ByteArrayOutputStream();
        writeVarInt(frame, payloadBytes.length);
        frame.writeBytes(payloadBytes);
        return frame.toByteArray();
    }

    private static byte[] playCommandFrame(int packetId, String command) {
        var payload = new ByteArrayOutputStream();
        writeVarInt(payload, packetId);
        writeString(payload, command);
        writeLong(payload, 0);
        writeLong(payload, 0);

        var payloadBytes = payload.toByteArray();
        var frame = new ByteArrayOutputStream();
        writeVarInt(frame, payloadBytes.length);
        frame.writeBytes(payloadBytes);
        return frame.toByteArray();
    }

    private static byte[] bungeeConnectFrame(String targetServer) {
        var payload = new ByteArrayOutputStream();
        writeVarInt(payload, 0x18);
        writeString(payload, "BungeeCord");
        writeBungeeUtf(payload, "Connect");
        writeBungeeUtf(payload, targetServer);

        var payloadBytes = payload.toByteArray();
        var frame = new ByteArrayOutputStream();
        writeVarInt(frame, payloadBytes.length);
        frame.writeBytes(payloadBytes);
        return frame.toByteArray();
    }

    private static byte[] loginSuccessFrame() {
        return packetFrame(0x02, 0);
    }

    private static byte[] joinGame1710Frame(int entityId, int gameMode, int dimension, int difficulty, String levelType) {
        var payload = new ByteArrayOutputStream();
        writeVarInt(payload, 0x01);
        payload.write((entityId >>> 24) & 0xFF);
        payload.write((entityId >>> 16) & 0xFF);
        payload.write((entityId >>> 8) & 0xFF);
        payload.write(entityId & 0xFF);
        payload.write(gameMode & 0xFF);
        payload.write(dimension & 0xFF);
        payload.write(difficulty & 0xFF);
        payload.write(100);
        writeString(payload, levelType);

        var payloadBytes = payload.toByteArray();
        var frame = new ByteArrayOutputStream();
        writeVarInt(frame, payloadBytes.length);
        frame.writeBytes(payloadBytes);
        return frame.toByteArray();
    }

    private static int packetId(byte[] frameBytes) {
        var frame = Unpooled.wrappedBuffer(frameBytes);
        try {
            var length = MinecraftVarInts.read(frame);
            var body = frame.readSlice(length);
            return MinecraftVarInts.read(body);
        } finally {
            frame.release();
        }
    }

    private static byte[] readMinecraftFrame(InputStream input) throws java.io.IOException {
        var lengthBytes = new ByteArrayOutputStream();
        var value = 0;
        var position = 0;
        while (position < 5) {
            var current = input.read();
            if (current < 0) {
                throw new java.io.EOFException("truncated frame length");
            }
            lengthBytes.write(current);
            value |= (current & 0x7F) << (position * 7);
            position++;
            if ((current & 0x80) == 0) {
                var body = input.readNBytes(value);
                if (body.length != value) {
                    throw new java.io.EOFException("truncated frame body");
                }
                var frame = new ByteArrayOutputStream();
                frame.writeBytes(lengthBytes.toByteArray());
                frame.writeBytes(body);
                return frame.toByteArray();
            }
        }
        throw new IllegalArgumentException("malformed frame length");
    }

    private static String loginDisconnectReason(byte[] frameBytes) {
        var frame = Unpooled.wrappedBuffer(frameBytes);
        try {
            var length = MinecraftVarInts.read(frame);
            var body = frame.readSlice(length);
            assertEquals(0, MinecraftVarInts.read(body));
            var json = readString(body);
            var prefix = "{\"text\":\"";
            var suffix = "\"}";
            return json.startsWith(prefix) && json.endsWith(suffix)
                    ? json.substring(prefix.length(), json.length() - suffix.length())
                    : json;
        } finally {
            frame.release();
        }
    }

    private static String systemChatMessage(byte[] frameBytes) {
        var frame = Unpooled.wrappedBuffer(frameBytes);
        try {
            var length = MinecraftVarInts.read(frame);
            var body = frame.readSlice(length);
            assertEquals(0x64, MinecraftVarInts.read(body));
            var json = readString(body);
            var prefix = "{\"text\":\"";
            var suffix = "\"}";
            return json.startsWith(prefix) && json.endsWith(suffix)
                    ? json.substring(prefix.length(), json.length() - suffix.length())
                    : json;
        } finally {
            frame.release();
        }
    }

    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String handshakeHost(byte[] frameBytes) {
        var frame = Unpooled.wrappedBuffer(frameBytes);
        try {
            var length = MinecraftVarInts.read(frame);
            var body = frame.readSlice(length);
            assertEquals(0, MinecraftVarInts.read(body));
            MinecraftVarInts.read(body);
            return readString(body);
        } finally {
            frame.release();
        }
    }

    private static String readString(io.netty.buffer.ByteBuf input) {
        var length = MinecraftVarInts.read(input);
        var bytes = new byte[length];
        input.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String offlineUuidNoDashes(String username) {
        return java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "");
    }

    private static byte[] concat(byte[] first, byte[] second) {
        var combined = new byte[first.length + second.length];
        System.arraycopy(first, 0, combined, 0, first.length);
        System.arraycopy(second, 0, combined, first.length, second.length);
        return combined;
    }

    private static void writeString(ByteArrayOutputStream output, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length);
        output.writeBytes(bytes);
    }

    private static void writeBungeeUtf(ByteArrayOutputStream output, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        output.write((bytes.length >>> 8) & 0xFF);
        output.write(bytes.length & 0xFF);
        output.writeBytes(bytes);
    }

    private static void writeLong(ByteArrayOutputStream output, long value) {
        for (var shift = 56; shift >= 0; shift -= 8) {
            output.write((int) ((value >>> shift) & 0xFF));
        }
    }

    private static void writeVarInt(ByteArrayOutputStream output, int value) {
        var current = value;
        do {
            var temp = current & 0x7F;
            current >>>= 7;
            if (current != 0) {
                temp |= 0x80;
            }
            output.write(temp);
        } while (current != 0);
    }

    private record TestRegisteredServer(
            ServerDescriptor descriptor,
            ServerHealth health,
            ServerLoad load,
            boolean draining) implements RegisteredServer {
    }

    private record SwitchBackendRead(byte[] loginBytes, byte[] playBytes) {
    }

    private record ReplacementBackendResolver(
            RegisteredServer initial,
            RegisteredServer replacement) implements BackendResolver, ServerTargetResolver {
        @Override
        public java.util.Optional<RegisteredServer> resolve(MinecraftHandshake request, java.net.SocketAddress remoteAddress) {
            return java.util.Optional.of(initial);
        }

        @Override
        public java.util.Optional<RegisteredServer> resolveTarget(String serverName) {
            return replacement.descriptor().name().equals(serverName)
                    ? java.util.Optional.of(replacement)
                    : java.util.Optional.empty();
        }
    }
}

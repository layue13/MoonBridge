package dev.strataproxy.network;

import dev.strataproxy.api.server.ProtocolRange;
import dev.strataproxy.api.server.RegisteredServer;
import dev.strataproxy.api.server.ServerCapability;
import dev.strataproxy.api.server.ServerDescriptor;
import dev.strataproxy.api.server.ServerHealth;
import dev.strataproxy.api.server.ServerLoad;
import dev.strataproxy.observability.ProxyMetrics;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
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
                assertEquals(new ProxyMetrics.PacketTraffic(1, handshake.length, 0), snapshot.packetTraffic().get(new ProxyMetrics.PacketTrafficKey(
                        "survival-1",
                        ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND,
                        "HANDSHAKE",
                        0)));
                assertEquals(new ProxyMetrics.PacketTraffic(1, pendingLoginFrame.length, 0), snapshot.packetTraffic().get(new ProxyMetrics.PacketTrafficKey(
                        "survival-1",
                        ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND,
                        "UNCOMPRESSED",
                        0x01)));
            }
        } finally {
            backendExecutor.shutdownNow();
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
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            var connections = metrics.snapshot().serverConnections().get("survival-1");
            if (connections != null && connections.activeConnections() == activeConnections) {
                return;
            }
            Thread.sleep(10);
        }
        var connections = metrics.snapshot().serverConnections().get("survival-1");
        assertEquals(activeConnections, connections == null ? 0 : connections.activeConnections());
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
}

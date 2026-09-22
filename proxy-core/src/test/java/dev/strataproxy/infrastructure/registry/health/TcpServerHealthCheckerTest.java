package dev.strataproxy.infrastructure.registry.health;

import dev.strataproxy.infrastructure.registry.memory.InMemoryServerRegistry;

import dev.strataproxy.domain.server.ProtocolRange;
import dev.strataproxy.domain.server.ServerDescriptor;
import dev.strataproxy.domain.server.ServerHealthStatus;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class TcpServerHealthCheckerTest {
    @Test
    void marksReachableBackendUp() throws Exception {
        try (var backend = new ServerSocket(0)) {
            var registry = new InMemoryServerRegistry();
            registry.register(descriptor("up", backend.getLocalPort()));

            try (var checker = new TcpServerHealthChecker(registry, Duration.ofSeconds(5), Duration.ofMillis(500))) {
                checker.checkAll();
            }

            assertEquals(ServerHealthStatus.UP, registry.get("up").orElseThrow().health().status());
        }
    }

    @Test
    void marksUnreachableBackendDown() throws Exception {
        int closedPort;
        try (var socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        var registry = new InMemoryServerRegistry();
        registry.register(descriptor("down", closedPort));

        try (var checker = new TcpServerHealthChecker(registry, Duration.ofSeconds(5), Duration.ofMillis(100))) {
            checker.checkAll();
        }

        assertEquals(ServerHealthStatus.DOWN, registry.get("down").orElseThrow().health().status());
    }

    @Test
    void marksMinecraftStatusBackendUp() throws Exception {
        var backendExecutor = Executors.newSingleThreadExecutor();
        try (var backend = new ServerSocket(0)) {
            backend.setSoTimeout(5_000);
            var served = backendExecutor.submit(() -> {
                try (var accepted = backend.accept()) {
                    accepted.setSoTimeout(5_000);
                    readMinecraftFrame(accepted.getInputStream());
                    readMinecraftFrame(accepted.getInputStream());
                    accepted.getOutputStream().write(statusResponseFrame("{\"version\":{\"name\":\"Paper\",\"protocol\":763},\"description\":{\"text\":\"ok\"}}"));
                    accepted.getOutputStream().flush();
                    return true;
                }
            });
            var registry = new InMemoryServerRegistry();
            registry.register(descriptor("status-up", backend.getLocalPort()));

            try (var checker = new TcpServerHealthChecker(registry, Duration.ofSeconds(5), Duration.ofMillis(500), "minecraft-status")) {
                checker.checkAll();
            }

            assertEquals(ServerHealthStatus.UP, registry.get("status-up").orElseThrow().health().status());
            assertEquals(true, served.get(5, TimeUnit.SECONDS));
        } finally {
            backendExecutor.shutdownNow();
        }
    }

    @Test
    void marksTcpOnlyBackendDownWhenMinecraftStatusModeIsEnabled() throws Exception {
        var backendExecutor = Executors.newSingleThreadExecutor();
        try (var backend = new ServerSocket(0)) {
            backend.setSoTimeout(5_000);
            var served = backendExecutor.submit(() -> {
                try (var accepted = backend.accept()) {
                    accepted.setSoTimeout(5_000);
                    readMinecraftFrame(accepted.getInputStream());
                    readMinecraftFrame(accepted.getInputStream());
                    accepted.getOutputStream().write(new byte[] {0x01, 0x7F});
                    accepted.getOutputStream().flush();
                    return true;
                }
            });
            var registry = new InMemoryServerRegistry();
            registry.register(descriptor("status-down", backend.getLocalPort()));

            try (var checker = new TcpServerHealthChecker(registry, Duration.ofSeconds(5), Duration.ofMillis(500), "minecraft-status")) {
                checker.checkAll();
            }

            assertEquals(ServerHealthStatus.DOWN, registry.get("status-down").orElseThrow().health().status());
            assertEquals(true, served.get(5, TimeUnit.SECONDS));
        } finally {
            backendExecutor.shutdownNow();
        }
    }

    private static ServerDescriptor descriptor(String name, int port) {
        return new ServerDescriptor(
                name,
                new InetSocketAddress("127.0.0.1", port),
                Set.of(),
                Set.of(),
                new ProtocolRange(0, Integer.MAX_VALUE, "any"),
                100,
                10,
                20,
                false,
                Map.of());
    }

    private static byte[] statusResponseFrame(String json) throws Exception {
        var payload = new ByteArrayOutputStream();
        writeVarInt(payload, 0);
        writeString(payload, json);
        return frame(payload.toByteArray());
    }

    private static byte[] frame(byte[] payload) {
        var frame = new ByteArrayOutputStream();
        writeVarInt(frame, payload.length);
        frame.writeBytes(payload);
        return frame.toByteArray();
    }

    private static byte[] readMinecraftFrame(java.io.InputStream input) throws Exception {
        var length = readVarInt(input);
        var body = input.readNBytes(length);
        if (body.length != length) {
            throw new java.io.EOFException("truncated frame");
        }
        return body;
    }

    private static int readVarInt(java.io.InputStream input) throws Exception {
        var value = 0;
        var position = 0;
        while (position < 5) {
            var current = input.read();
            if (current < 0) {
                throw new java.io.EOFException("truncated VarInt");
            }
            value |= (current & 0x7F) << (position * 7);
            if ((current & 0x80) == 0) {
                return value;
            }
            position++;
        }
        throw new IllegalArgumentException("malformed VarInt");
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
}

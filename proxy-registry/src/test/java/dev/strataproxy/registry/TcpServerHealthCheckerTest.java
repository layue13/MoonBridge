package dev.strataproxy.registry;

import dev.strataproxy.api.server.ProtocolRange;
import dev.strataproxy.api.server.ServerDescriptor;
import dev.strataproxy.api.server.ServerHealthStatus;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

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
}

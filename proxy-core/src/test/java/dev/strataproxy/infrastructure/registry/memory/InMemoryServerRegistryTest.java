package dev.strataproxy.infrastructure.registry.memory;

import dev.strataproxy.domain.server.ProtocolRange;
import dev.strataproxy.domain.server.ServerCapability;
import dev.strataproxy.domain.server.ServerDescriptor;
import dev.strataproxy.domain.server.ServerHealth;
import dev.strataproxy.domain.server.ServerHealthStatus;
import dev.strataproxy.domain.server.ServerLoad;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InMemoryServerRegistryTest {
    @Test
    void registerRejectsDuplicateNames() {
        var registry = new InMemoryServerRegistry();
        registry.register(descriptor("survival-1", 25565, Set.of("blue"), 100, 120, false));

        assertThrows(IllegalArgumentException.class,
                () -> registry.register(descriptor("survival-1", 25566, Set.of("green"), 200, 240, false)));
    }

    @Test
    void registerOrReplaceUpdatesDescriptorWithoutDroppingRuntimeState() {
        var registry = new InMemoryServerRegistry();
        registry.register(descriptor("survival-1", 25565, Set.of("blue"), 100, 120, false));
        registry.updateHealth("survival-1", new ServerHealth(
                ServerHealthStatus.DEGRADED,
                42,
                0.2d,
                "canary",
                Instant.now()));
        registry.updateLoad("survival-1", new ServerLoad(12, 100, 120, 1024, 2048, 300, 1.5d));

        var replaced = registry.registerOrReplace(descriptor("survival-1", 25566, Set.of("green", "survival"), 200, 240, true));

        assertEquals(25566, replaced.descriptor().address().getPort());
        assertEquals(Set.of("green", "survival"), replaced.descriptor().tags());
        assertEquals(200, replaced.load().softCapacity());
        assertEquals(240, replaced.load().hardCapacity());
        assertEquals(12, replaced.load().players());
        assertEquals(1024, replaced.load().inboundBytesPerSecond());
        assertEquals(ServerHealthStatus.DEGRADED, replaced.health().status());
        assertEquals(42, replaced.health().backendPingMillis());
        assertTrue(replaced.draining());
    }

    private static ServerDescriptor descriptor(
            String name,
            int port,
            Set<String> tags,
            int softCapacity,
            int hardCapacity,
            boolean drainMode) {
        return new ServerDescriptor(
                name,
                new InetSocketAddress("127.0.0.1", port),
                tags,
                Set.of(ServerCapability.LARGE_PAYLOAD),
                new ProtocolRange(0, Integer.MAX_VALUE, "any"),
                100,
                softCapacity,
                hardCapacity,
                drainMode,
                Map.of("host", "survival.local"));
    }
}

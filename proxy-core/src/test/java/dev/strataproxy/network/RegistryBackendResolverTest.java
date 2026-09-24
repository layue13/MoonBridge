package dev.strataproxy.network;

import dev.strataproxy.api.server.ProtocolRange;
import dev.strataproxy.api.server.ServerDescriptor;
import dev.strataproxy.api.server.ServerHealth;
import dev.strataproxy.api.server.ServerHealthStatus;
import dev.strataproxy.api.server.ServerLoad;
import dev.strataproxy.registry.InMemoryServerRegistry;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RegistryBackendResolverTest {
    @Test
    void normalizesForgeMarkedHostAndSelectsItsBackend() {
        var registry = new InMemoryServerRegistry();
        registry.register(descriptor("a-default", Map.of(), anyProtocol()));
        registry.register(descriptor("survival", Map.of("host", "play.example.net"), anyProtocol()));

        var selected = new RegistryBackendResolver(registry).resolve(
                new MinecraftHandshake(5, "play.example.net.\0FML\0", 25565, 2),
                new InetSocketAddress("127.0.0.1", 50000));

        assertEquals("survival", selected.orElseThrow().descriptor().name());
    }

    @Test
    void unknownHostUsesDeterministicEligibleDefault() {
        var registry = new InMemoryServerRegistry();
        registry.register(descriptor("z-backend", Map.of(), anyProtocol()));
        registry.register(descriptor("a-backend", Map.of(), anyProtocol()));

        var selected = new RegistryBackendResolver(registry).resolve(
                new MinecraftHandshake(5, "unknown.example.net", 25565, 2),
                new InetSocketAddress("127.0.0.1", 50000));

        assertEquals("a-backend", selected.orElseThrow().descriptor().name());
    }

    @Test
    void unavailableKnownHostDoesNotFallThroughToAnotherGameplay() {
        var registry = new InMemoryServerRegistry();
        registry.register(descriptor("a-default", Map.of(), anyProtocol()));
        registry.register(descriptor("island", Map.of("host", "island.example.net"), anyProtocol()));
        registry.updateDrainMode("island", true);

        var selected = new RegistryBackendResolver(registry).resolve(
                new MinecraftHandshake(5, "island.example.net", 25565, 2),
                new InetSocketAddress("127.0.0.1", 50000));

        assertTrue(selected.isEmpty());
    }

    @Test
    void excludesUnhealthyOrFullBackendsAndChecksExplicitTransferProtocol() {
        var registry = new InMemoryServerRegistry();
        registry.register(descriptor("a-down", Map.of(), anyProtocol()));
        registry.register(descriptor("b-full", Map.of(), anyProtocol()));
        registry.register(descriptor("legacy", Map.of(), new ProtocolRange(5, 5, "1.7.10")));
        registry.updateHealth("a-down", new ServerHealth(ServerHealthStatus.DOWN, -1, 1.0d, "down", Instant.now()));
        registry.updateLoad("b-full", new ServerLoad(10, 8, 10, 0, 0, 0, 0));
        var resolver = new RegistryBackendResolver(registry);

        assertEquals("legacy", resolver.resolve(
                new MinecraftHandshake(5, "unknown.example.net", 25565, 2),
                new InetSocketAddress("127.0.0.1", 50000)).orElseThrow().descriptor().name());
        assertTrue(resolver.resolveTarget("legacy", 5).isPresent());
        assertFalse(resolver.resolveTarget("legacy", 763).isPresent());
    }

    private static ServerDescriptor descriptor(String name, Map<String, String> metadata, ProtocolRange protocolRange) {
        return new ServerDescriptor(
                name,
                new InetSocketAddress("127.0.0.1", 25565),
                Set.of(),
                Set.of(),
                protocolRange,
                100,
                8,
                10,
                false,
                metadata);
    }

    private static ProtocolRange anyProtocol() {
        return new ProtocolRange(0, Integer.MAX_VALUE, "any");
    }
}

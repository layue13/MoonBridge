package dev.strataproxy.network;

import dev.strataproxy.api.server.ProtocolRange;
import dev.strataproxy.api.server.ServerDescriptor;
import dev.strataproxy.plugin.route.RouteDecision;
import dev.strataproxy.plugin.route.RouteStage;
import dev.strataproxy.plugin.service.PlayerIdentity;
import dev.strataproxy.registry.InMemoryServerRegistry;
import dev.strataproxy.route.StageRouteEngine;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StagedBackendResolverTest {
    @Test
    void initialPolicyReceivesPlayerIdentityAndCanSelectAnotherEligibleBackend() throws Exception {
        var registry = new InMemoryServerRegistry();
        registry.register(server("a-spawn"));
        registry.register(server("b-personal-spawn"));
        try (var routes = new StageRouteEngine(Duration.ofSeconds(1))) {
            var observedName = new AtomicReference<String>();
            routes.forPlugin("spawn").register(RouteStage.INITIAL, 100, context -> {
                assertEquals("play.example.net", context.requestedHost());
                assertEquals(5, context.protocolVersion());
                observedName.set(context.playerName());
                return CompletableFuture.completedFuture(RouteDecision.select("b-personal-spawn"));
            });
            var resolver = new StagedBackendResolver(registry, routes);

            var selected = resolver.resolveInitial(
                    new MinecraftHandshake(5, "play.example.net", 25565, 2),
                    new InetSocketAddress("127.0.0.1", 50000),
                    new PlayerIdentity(UUID.randomUUID(), "connection-1"), "Steve")
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertEquals("Steve", observedName.get());
            assertEquals("b-personal-spawn", selected.orElseThrow().descriptor().name());
        }
    }

    @Test
    void pluginSelectionStillObeysCoreEligibilityAndRejectionDoesNotFallBack() throws Exception {
        var registry = new InMemoryServerRegistry();
        registry.register(server("a-spawn"));
        registry.register(server("b-island"));
        registry.updateDrainMode("b-island", true);
        try (var routes = new StageRouteEngine(Duration.ofSeconds(1))) {
            var registration = routes.forPlugin("islands").register(RouteStage.INITIAL, 10,
                    context -> CompletableFuture.completedFuture(RouteDecision.select("b-island")));
            var resolver = new StagedBackendResolver(registry, routes);
            var handshake = new MinecraftHandshake(5, "play.example.net", 25565, 2);
            var remote = new InetSocketAddress("127.0.0.1", 50000);
            var player = new PlayerIdentity(null, "connection-1");

            assertTrue(resolver.resolveInitial(handshake, remote, player, "Steve")
                    .toCompletableFuture().get(2, TimeUnit.SECONDS).isEmpty());
            registration.close();
            routes.forPlugin("islands").register(RouteStage.INITIAL, 10,
                    context -> CompletableFuture.completedFuture(RouteDecision.reject("maintenance")));
            assertTrue(resolver.resolveInitial(handshake, remote, player, "Steve")
                    .toCompletableFuture().get(2, TimeUnit.SECONDS).isEmpty());
        }
    }

    private static ServerDescriptor server(String name) {
        return new ServerDescriptor(name, new InetSocketAddress("127.0.0.1", 25565), Set.of(), Set.of(),
                new ProtocolRange(0, Integer.MAX_VALUE, "any"), 100, 100, 120, false, Map.of());
    }
}

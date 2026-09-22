package dev.strataproxy.network;

import dev.strataproxy.api.server.ProtocolRange;
import dev.strataproxy.api.server.RegisteredServer;
import dev.strataproxy.api.server.ServerDescriptor;
import dev.strataproxy.api.server.ServerHealth;
import dev.strataproxy.api.server.ServerLoad;
import dev.strataproxy.routing.RoutingDecision;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RoutingBackendResolverTest {
    @Test
    void normalizesForgeMarkedAndTrailingDotVirtualHostsBeforeRouting() {
        var selected = server("modded-1");
        var routes = new ArrayList<String>();
        var resolver = new RoutingBackendResolver(request -> {
            routes.add(request.route());
            if ("play.example.net".equals(request.route())) {
                return new RoutingDecision.Selected(selected, 100);
            }
            return new RoutingDecision.Rejected("no match");
        });

        var result = resolver.resolve(
                new MinecraftHandshake(763, "play.example.net.\0FML\0", 25565, 2),
                new InetSocketAddress("127.0.0.1", 50000));

        assertTrue(result.isPresent());
        assertEquals(List.of("play.example.net"), routes);
    }

    @Test
    void fallsBackToRouteAgnosticSelectionAfterNormalizedHostMiss() {
        var selected = server("fallback-1");
        var routes = new ArrayList<String>();
        var resolver = new RoutingBackendResolver(request -> {
            routes.add(request.route());
            if (request.route().isBlank()) {
                return new RoutingDecision.Selected(selected, 100);
            }
            return new RoutingDecision.Rejected("no match");
        });

        var result = resolver.resolve(
                new MinecraftHandshake(763, "unknown.example.net\0FML\0", 25565, 2),
                new InetSocketAddress("127.0.0.1", 50000));

        assertTrue(result.isPresent());
        assertEquals(List.of("unknown.example.net", ""), routes);
    }

    @Test
    void explicitTransferTargetMustAcceptClientProtocol() {
        var legacy = server("legacy-1", new ProtocolRange(5, 5, "1.7.10"));
        var resolver = new RoutingBackendResolver(
                ignored -> new RoutingDecision.Rejected("unused"),
                new SingleServerRegistry(legacy));

        assertTrue(resolver.resolveTarget("legacy-1", 5).isPresent());
        assertFalse(resolver.resolveTarget("legacy-1", 763).isPresent());
    }

    private static RegisteredServer server(String name) {
        return server(name, new ProtocolRange(0, Integer.MAX_VALUE, "any"));
    }

    private static RegisteredServer server(String name, ProtocolRange protocolRange) {
        return new TestRegisteredServer(
                new ServerDescriptor(
                        name,
                        new InetSocketAddress("127.0.0.1", 25565),
                        Set.of("modded"),
                        Set.of(),
                        protocolRange,
                        100,
                        100,
                        120,
                        false,
                        Map.of()),
                ServerHealth.up(1),
                new ServerLoad(0, 100, 120, 0, 0, 0, 0),
                false);
    }

    private record TestRegisteredServer(
            ServerDescriptor descriptor,
            ServerHealth health,
            ServerLoad load,
            boolean draining) implements RegisteredServer {
    }

    private record SingleServerRegistry(RegisteredServer server) implements dev.strataproxy.api.server.ServerRegistry {
        @Override
        public RegisteredServer register(ServerDescriptor descriptor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RegisteredServer registerOrReplace(ServerDescriptor descriptor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean unregister(String name, dev.strataproxy.api.server.DrainPolicy policy) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RegisteredServer> get(String name) {
            return server.descriptor().name().equals(name) ? Optional.of(server) : Optional.empty();
        }

        @Override
        public java.util.Collection<RegisteredServer> snapshot() {
            return List.of(server);
        }

        @Override
        public void updateHealth(String name, ServerHealth health) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateLoad(String name, ServerLoad load) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateDrainMode(String name, boolean drainMode) {
            throw new UnsupportedOperationException();
        }
    }
}

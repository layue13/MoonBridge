package dev.strataproxy.routing;

import dev.strataproxy.api.server.ProtocolRange;
import dev.strataproxy.api.server.ServerCapability;
import dev.strataproxy.api.server.ServerDescriptor;
import dev.strataproxy.api.server.ServerLoad;
import dev.strataproxy.registry.InMemoryServerRegistry;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WeightedHealthAwareRouterTest {
    @Test
    void selectsHealthyServerMatchingTagsCapabilitiesAndProtocol() {
        var registry = new InMemoryServerRegistry();
        registry.register(new ServerDescriptor(
                "survival-1",
                new InetSocketAddress("127.0.0.1", 25565),
                Set.of("survival", "forge"),
                Set.of(ServerCapability.LARGE_PAYLOAD),
                new ProtocolRange(763, 763, "1.20.1"),
                100,
                100,
                120,
                false,
                Map.of()));

        var router = new WeightedHealthAwareRouter(registry);
        var decision = router.route(new RoutingRequest(
                "survival",
                Set.of("survival"),
                Set.of(ServerCapability.LARGE_PAYLOAD),
                763,
                new InetSocketAddress("127.0.0.1", 50000)));

        assertInstanceOf(RoutingDecision.Selected.class, decision);
    }

    @Test
    void rejectsWhenRequestedRouteDoesNotMatchNameTagOrMetadata() {
        var registry = new InMemoryServerRegistry();
        registry.register(new ServerDescriptor(
                "survival-1",
                new InetSocketAddress("127.0.0.1", 25565),
                Set.of("survival", "forge"),
                Set.of(ServerCapability.LARGE_PAYLOAD),
                new ProtocolRange(763, 763, "1.20.1"),
                100,
                100,
                120,
                false,
                Map.of("host", "survival.example.net")));

        var router = new WeightedHealthAwareRouter(registry);
        var decision = router.route(new RoutingRequest(
                "creative.example.net",
                Set.of(),
                Set.of(ServerCapability.LARGE_PAYLOAD),
                763,
                new InetSocketAddress("127.0.0.1", 50000)));

        assertInstanceOf(RoutingDecision.Rejected.class, decision);
    }

    @Test
    void ignoresDrainedServersUntilUndrained() {
        var registry = new InMemoryServerRegistry();
        registry.register(new ServerDescriptor(
                "survival-1",
                new InetSocketAddress("127.0.0.1", 25565),
                Set.of("survival"),
                Set.of(ServerCapability.LARGE_PAYLOAD),
                new ProtocolRange(763, 763, "1.20.1"),
                100,
                100,
                120,
                false,
                Map.of()));
        var router = new WeightedHealthAwareRouter(registry);
        var request = new RoutingRequest(
                "survival",
                Set.of(),
                Set.of(ServerCapability.LARGE_PAYLOAD),
                763,
                new InetSocketAddress("127.0.0.1", 50000));

        registry.updateDrainMode("survival-1", true);
        assertInstanceOf(RoutingDecision.Rejected.class, router.route(request));

        registry.updateDrainMode("survival-1", false);
        assertInstanceOf(RoutingDecision.Selected.class, router.route(request));
    }

    @Test
    void explainsCandidateEligibilityAndRejectionReasons() {
        var registry = new InMemoryServerRegistry();
        registry.register(descriptor("survival-ready", 100));
        registry.register(descriptor("survival-draining", 100));
        registry.register(new ServerDescriptor(
                "creative-1",
                new InetSocketAddress("127.0.0.1", 25566),
                Set.of("creative"),
                Set.of(ServerCapability.LARGE_PAYLOAD),
                new ProtocolRange(763, 763, "1.20.1"),
                100,
                100,
                120,
                false,
                Map.of()));
        registry.updateDrainMode("survival-draining", true);
        var router = new WeightedHealthAwareRouter(registry);

        var explanation = router.explain(new RoutingRequest(
                "survival",
                Set.of("survival"),
                Set.of(ServerCapability.LARGE_PAYLOAD),
                763,
                new InetSocketAddress("127.0.0.1", 50000)));

        assertEquals("survival-ready", explanation.getFirst().serverName());
        assertTrue(explanation.getFirst().eligible());
        assertTrue(explanation.stream().anyMatch(candidate ->
                candidate.serverName().equals("survival-draining") && candidate.reason().equals("draining")));
        assertTrue(explanation.stream().anyMatch(candidate ->
                candidate.serverName().equals("creative-1") && candidate.reason().equals("route_mismatch")));
    }

    @Test
    void distributesTrafficAcrossWeightedCandidatesForGrayRollouts() {
        var registry = new InMemoryServerRegistry();
        registry.register(descriptor("survival-blue", 100));
        registry.register(descriptor("survival-canary", 25));
        var router = new WeightedHealthAwareRouter(registry);

        var blue = 0;
        var canary = 0;
        for (var port = 10_000; port < 11_000; port++) {
            var decision = router.route(new RoutingRequest(
                    "survival",
                    Set.of("survival"),
                    Set.of(ServerCapability.LARGE_PAYLOAD),
                    763,
                    new InetSocketAddress("127.0.0.1", port)));
            var selected = assertInstanceOf(RoutingDecision.Selected.class, decision);
            if (selected.server().descriptor().name().equals("survival-blue")) {
                blue++;
            } else if (selected.server().descriptor().name().equals("survival-canary")) {
                canary++;
            }
        }

        assertTrue(blue > canary);
        assertTrue(canary > 120, "canary should receive real rollout traffic");
        assertTrue(canary < 280, "canary share should remain bounded by its lower weight");
    }

    @Test
    void keepsSelectionStableForSameRemoteAddress() {
        var registry = new InMemoryServerRegistry();
        registry.register(descriptor("survival-blue", 100));
        registry.register(descriptor("survival-canary", 25));
        var router = new WeightedHealthAwareRouter(registry);
        var request = new RoutingRequest(
                "survival",
                Set.of("survival"),
                Set.of(ServerCapability.LARGE_PAYLOAD),
                763,
                new InetSocketAddress("127.0.0.1", 25_000));

        var first = assertInstanceOf(RoutingDecision.Selected.class, router.route(request));
        for (var attempt = 0; attempt < 20; attempt++) {
            var next = assertInstanceOf(RoutingDecision.Selected.class, router.route(request));
            assertEquals(first.server().descriptor().name(), next.server().descriptor().name());
        }
    }

    @Test
    void capacityPressureShiftsSelectionTowardEmptierBackends() {
        var registry = new InMemoryServerRegistry();
        registry.register(descriptor("survival-fuller", 100));
        registry.register(descriptor("survival-empty", 100));
        registry.updateLoad("survival-fuller", new ServerLoad(95, 100, 120, 0, 0, 0, 0));
        var router = new WeightedHealthAwareRouter(registry);

        var fuller = 0;
        var empty = 0;
        for (var port = 20_000; port < 21_000; port++) {
            var selected = assertInstanceOf(RoutingDecision.Selected.class, router.route(new RoutingRequest(
                    "survival",
                    Set.of("survival"),
                    Set.of(ServerCapability.LARGE_PAYLOAD),
                    763,
                    new InetSocketAddress("127.0.0.1", port))));
            if (selected.server().descriptor().name().equals("survival-fuller")) {
                fuller++;
            } else if (selected.server().descriptor().name().equals("survival-empty")) {
                empty++;
            }
        }

        assertTrue(empty > fuller * 10, "capacity pressure should strongly reduce effective weight");
    }

    private static ServerDescriptor descriptor(String name, int weight) {
        return new ServerDescriptor(
                name,
                new InetSocketAddress("127.0.0.1", 25565),
                Set.of("survival", "forge"),
                Set.of(ServerCapability.LARGE_PAYLOAD),
                new ProtocolRange(763, 763, "1.20.1"),
                weight,
                100,
                120,
                false,
                Map.of());
    }
}

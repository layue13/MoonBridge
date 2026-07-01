package dev.strataproxy.routing;

import dev.strataproxy.api.server.RegisteredServer;
import dev.strataproxy.api.server.ServerRegistry;

import java.util.List;
import java.util.Objects;

/**
 * Router that combines configured weights with health, load, latency, and event-loop pressure.
 *
 * <p>Eligible servers are selected with deterministic weighted sampling. The same request identity tends to make the
 * same choice while healthier and less-loaded servers receive proportionally more traffic.</p>
 */
public final class WeightedHealthAwareRouter implements ServerRouter {
    private final ServerRegistry registry;

    /**
     * Creates a router backed by a live server registry.
     *
     * @param registry registry supplying server snapshots
     */
    public WeightedHealthAwareRouter(ServerRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    /** Provides route. */
    public RoutingDecision route(RoutingRequest request) {
        return explain(request).stream()
                .filter(RouteCandidate::eligible)
                .filter(candidate -> candidate.effectiveWeight() > 0.0d)
                .min(java.util.Comparator
                        .comparingDouble(RouteCandidate::selectionKey)
                        .thenComparing(RouteCandidate::serverName))
                .map(candidate -> new RoutingDecision.Selected(candidate.server(), candidate.effectiveWeight()))
                .<RoutingDecision>map(selected -> selected)
                .orElseGet(() -> new RoutingDecision.Rejected("no healthy backend matched route constraints"));
    }

    /**
     * Explains how each registered server would be treated for a request.
     *
     * @param request route constraints
     * @return candidates ordered with eligible servers first and best selection keys first
     */
    public List<RouteCandidate> explain(RoutingRequest request) {
        return registry.snapshot().stream()
                .map(server -> explain(request, server))
                .sorted(java.util.Comparator
                        .comparing(RouteCandidate::eligible).reversed()
                        .thenComparingDouble(RouteCandidate::selectionKey)
                        .thenComparing(RouteCandidate::serverName))
                .toList();
    }

    private static RouteCandidate explain(RoutingRequest request, RegisteredServer server) {
        var reason = rejectionReason(request, server);
        var eligible = reason.isBlank();
        var weight = eligible ? effectiveWeight(server) : 0.0d;
        var key = eligible ? weightedKey(request, server) : Double.POSITIVE_INFINITY;
        return new RouteCandidate(server, server.descriptor().name(), eligible, reason, weight, key);
    }

    private static String rejectionReason(RoutingRequest request, RegisteredServer server) {
        var descriptor = server.descriptor();
        if (server.draining()) {
            return "draining";
        }
        if (!server.health().canReceiveNewConnections()) {
            return "health_" + server.health().status().name().toLowerCase(java.util.Locale.ROOT);
        }
        if (server.load().isHardFull()) {
            return "hard_full";
        }
        if (!descriptor.protocolRange().accepts(request.protocolVersion())) {
            return "protocol_mismatch";
        }
        if (!routeMatches(request.route(), server)) {
            return "route_mismatch";
        }
        if (!descriptor.tags().containsAll(request.requiredTags())) {
            return "tag_mismatch";
        }
        if (!descriptor.capabilities().containsAll(request.requiredCapabilities())) {
            return "capability_mismatch";
        }
        return "";
    }

    private static boolean routeMatches(String route, RegisteredServer server) {
        if (route == null || route.isBlank()) {
            return true;
        }
        var normalizedRoute = route.toLowerCase(java.util.Locale.ROOT);
        var descriptor = server.descriptor();
        if (descriptor.name().equalsIgnoreCase(route)) {
            return true;
        }
        if (descriptor.tags().stream().anyMatch(tag -> tag.equalsIgnoreCase(route))) {
            return true;
        }
        var metadata = descriptor.metadata();
        var host = metadata.get("host");
        var routeAlias = metadata.get("route");
        return normalizedRoute.equalsIgnoreCase(host) || normalizedRoute.equalsIgnoreCase(routeAlias);
    }

    private static double effectiveWeight(RegisteredServer server) {
        var descriptor = server.descriptor();
        var health = server.health();
        var load = server.load();
        var baseWeight = Math.max(0.0d, descriptor.weight());
        if (baseWeight == 0.0d) {
            return 0.0d;
        }
        var capacityFactor = Math.max(0.05d, 1.0d - Math.min(1.0d, load.capacityPressure()));
        var failureFactor = Math.max(0.05d, 1.0d - health.recentFailureRate());
        var latencyFactor = health.backendPingMillis() < 0
                ? 1.0d
                : 1.0d / (1.0d + Math.max(0.0d, health.backendPingMillis()) / 100.0d);
        var eventLoopFactor = 1.0d / (1.0d + Math.max(0.0d, load.eventLoopDelayMillis()) / 10.0d);
        return baseWeight * capacityFactor * failureFactor * latencyFactor * eventLoopFactor;
    }

    private static double weightedKey(RoutingRequest request, RegisteredServer server) {
        var weight = effectiveWeight(server);
        if (weight <= 0.0d) {
            return Double.POSITIVE_INFINITY;
        }
        var uniform = uniformHash(selectionKey(request, server));
        return -Math.log(uniform) / weight;
    }

    private static String selectionKey(RoutingRequest request, RegisteredServer server) {
        var remote = request.remoteAddress();
        var remoteKey = remote == null ? "" : remote.getAddress().getHostAddress() + ":" + remote.getPort();
        return nullToEmpty(request.route()) + "|" + request.protocolVersion() + "|" + remoteKey + "|" + server.descriptor().name();
    }

    private static double uniformHash(String key) {
        var hash = 0xcbf29ce484222325L;
        for (var index = 0; index < key.length(); index++) {
            hash ^= key.charAt(index);
            hash *= 0x100000001b3L;
        }
        var mixed = mix64(hash);
        var bits = (mixed >>> 11) & ((1L << 53) - 1);
        return (bits + 1.0d) / ((1L << 53) + 1.0d);
    }

    private static long mix64(long value) {
        var mixed = value;
        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        mixed *= 0xc4ceb9fe1a85ec53L;
        mixed ^= mixed >>> 33;
        return mixed;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * Diagnostic view of a server considered during routing.
     *
     * @param server backend server
     * @param serverName backend name copied for stable sorting and reporting
     * @param eligible whether the server passed all hard routing checks
     * @param reason rejection reason, or blank when eligible
     * @param effectiveWeight configured weight after health and load penalties
     * @param selectionKey deterministic weighted-sampling key; lower values are preferred
     */
    public record RouteCandidate(
            RegisteredServer server,
            String serverName,
            boolean eligible,
            String reason,
            double effectiveWeight,
            double selectionKey) {
    }
}

package dev.strataproxy.network;

import dev.strataproxy.api.server.RegisteredServer;
import dev.strataproxy.api.server.ServerRegistry;
import dev.strataproxy.routing.RoutingDecision;
import dev.strataproxy.routing.RoutingRequest;
import dev.strataproxy.routing.ServerRouter;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Optional;
import java.util.Set;

public final class RoutingBackendResolver implements BackendResolver, ServerTargetResolver {
    private final ServerRouter router;
    private final ServerRegistry registry;

    public RoutingBackendResolver(ServerRouter router) {
        this(router, null);
    }

    public RoutingBackendResolver(ServerRouter router, ServerRegistry registry) {
        this.router = router;
        this.registry = registry;
    }

    @Override
    public Optional<RegisteredServer> resolve(MinecraftHandshake handshake, SocketAddress remoteAddress) {
        var inetRemote = remoteAddress instanceof InetSocketAddress inet ? inet : InetSocketAddress.createUnresolved("unknown", 0);
        var route = normalizeRouteHost(handshake.requestedHost());
        var hostSpecific = new RoutingRequest(
                route,
                Set.of(),
                Set.of(),
                handshake.protocolVersion(),
                inetRemote);
        var selected = select(hostSpecific);
        if (selected.isPresent()) {
            return selected;
        }
        return select(new RoutingRequest(
                "",
                Set.of(),
                Set.of(),
                handshake.protocolVersion(),
                inetRemote));
    }

    @Override
    public Optional<RegisteredServer> resolveTarget(String serverName) {
        if (registry == null || serverName == null || serverName.isBlank()) {
            return Optional.empty();
        }
        return registry.get(serverName.trim())
                .filter(server -> !server.draining())
                .filter(server -> server.health().canReceiveNewConnections())
                .filter(server -> !server.load().isHardFull());
    }

    private Optional<RegisteredServer> select(RoutingRequest request) {
        return switch (router.route(request)) {
            case RoutingDecision.Selected selected -> Optional.of(selected.server());
            case RoutingDecision.Rejected ignored -> Optional.empty();
        };
    }

    static String normalizeRouteHost(String requestedHost) {
        if (requestedHost == null || requestedHost.isBlank()) {
            return "";
        }
        var host = requestedHost.strip();
        var nul = host.indexOf('\0');
        if (nul >= 0) {
            host = host.substring(0, nul);
        }
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return host;
    }
}

package dev.strataproxy.infrastructure.minecraft;

import dev.strataproxy.domain.server.RegisteredServer;
import dev.strataproxy.domain.server.ServerRegistry;
import dev.strataproxy.domain.routing.RoutingDecision;
import dev.strataproxy.domain.routing.RoutingRequest;
import dev.strataproxy.domain.routing.ServerRouter;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves backend servers from Minecraft handshakes by delegating to a {@link ServerRouter}.
 */
public final class RoutingBackendResolver implements BackendResolver, ServerTargetResolver {
    private final ServerRouter router;
    private final ServerRegistry registry;

    /**
     * Creates a resolver without explicit transfer-target lookup.
     *
     * @param router router used for handshake-based backend selection
     */
    public RoutingBackendResolver(ServerRouter router) {
        this(router, null);
    }

    /**
     * Creates a resolver with optional explicit transfer-target lookup.
     *
     * @param router router used for handshake-based backend selection
     * @param registry optional registry used for explicit transfer targets
     */
    public RoutingBackendResolver(ServerRouter router, ServerRegistry registry) {
        this.router = router;
        this.registry = registry;
    }

    @Override
    /** Provides resolve. */
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
    /** Provides resolve target. */
    public Optional<RegisteredServer> resolveTarget(String serverName) {
        return resolveTarget(serverName, -1);
    }

    @Override
    /** Provides resolve target. */
    public Optional<RegisteredServer> resolveTarget(String serverName, int protocolVersion) {
        if (registry == null || serverName == null || serverName.isBlank()) {
            return Optional.empty();
        }
        return registry.get(serverName.trim())
                .filter(server -> protocolVersion < 0 || server.descriptor().protocolRange().accepts(protocolVersion))
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

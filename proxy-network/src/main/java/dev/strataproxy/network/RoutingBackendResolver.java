package dev.strataproxy.network;

import dev.strataproxy.api.server.RegisteredServer;
import dev.strataproxy.routing.RoutingDecision;
import dev.strataproxy.routing.RoutingRequest;
import dev.strataproxy.routing.ServerRouter;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Optional;
import java.util.Set;

public final class RoutingBackendResolver implements BackendResolver {
    private final ServerRouter router;

    public RoutingBackendResolver(ServerRouter router) {
        this.router = router;
    }

    @Override
    public Optional<RegisteredServer> resolve(MinecraftHandshake handshake, SocketAddress remoteAddress) {
        var inetRemote = remoteAddress instanceof InetSocketAddress inet ? inet : InetSocketAddress.createUnresolved("unknown", 0);
        var hostSpecific = new RoutingRequest(
                handshake.requestedHost(),
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

    private Optional<RegisteredServer> select(RoutingRequest request) {
        return switch (router.route(request)) {
            case RoutingDecision.Selected selected -> Optional.of(selected.server());
            case RoutingDecision.Rejected ignored -> Optional.empty();
        };
    }
}

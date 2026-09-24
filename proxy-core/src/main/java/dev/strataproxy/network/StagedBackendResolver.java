package dev.strataproxy.network;

import dev.strataproxy.api.server.RegisteredServer;
import dev.strataproxy.api.server.ServerRegistry;
import dev.strataproxy.plugin.route.RouteContext;
import dev.strataproxy.plugin.route.RouteDecision;
import dev.strataproxy.plugin.route.RouteStage;
import dev.strataproxy.plugin.service.PlayerIdentity;
import dev.strataproxy.route.StageRouteEngine;

import java.net.SocketAddress;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Applies the initial plugin stage, then the minimal registry default. */
public final class StagedBackendResolver implements BackendResolver, ServerTargetResolver {
    private final RegistryBackendResolver fallback;
    private final StageRouteEngine routes;

    public StagedBackendResolver(
            ServerRegistry registry,
            StageRouteEngine routes) {
        this.fallback = new RegistryBackendResolver(registry);
        this.routes = routes;
    }

    @Override
    public Optional<RegisteredServer> resolve(MinecraftHandshake handshake, SocketAddress remoteAddress) {
        return fallback.resolve(handshake, remoteAddress);
    }

    @Override
    public CompletionStage<Optional<RegisteredServer>> resolveInitial(
            MinecraftHandshake handshake,
            SocketAddress remoteAddress,
            PlayerIdentity identity,
            String playerName) {
        var host = RegistryBackendResolver.normalizeRouteHost(handshake.requestedHost());
        var context = new RouteContext(RouteStage.INITIAL, host, handshake.protocolVersion(),
                identity, playerName, "");
        return routes.evaluate(context).thenApply(decision -> select(decision, handshake, remoteAddress));
    }

    @Override
    public Optional<RegisteredServer> resolveTarget(String serverName) {
        return fallback.resolveTarget(serverName);
    }

    @Override
    public Optional<RegisteredServer> resolveTarget(String serverName, int protocolVersion) {
        return fallback.resolveTarget(serverName, protocolVersion);
    }

    private Optional<RegisteredServer> select(
            RouteDecision decision, MinecraftHandshake handshake, SocketAddress remoteAddress) {
        return switch (decision.kind()) {
            case PASS -> fallback.resolve(handshake, remoteAddress);
            case SELECT -> fallback.resolveTarget(decision.serverName(), handshake.protocolVersion());
            case REJECT -> Optional.empty();
        };
    }
}

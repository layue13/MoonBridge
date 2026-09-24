package dev.strataproxy.plugin.runtime;

import dev.strataproxy.network.NettyProxyNetworkServer;
import dev.strataproxy.network.ProxyMetrics;
import dev.strataproxy.plugin.service.PlayerIdentity;
import dev.strataproxy.plugin.service.PlayerService;
import dev.strataproxy.plugin.service.PlayerTransfer;
import dev.strataproxy.plugin.service.PlayerView;
import dev.strataproxy.plugin.service.ServerView;
import dev.strataproxy.plugin.route.RouteContext;
import dev.strataproxy.plugin.route.RouteDecision;
import dev.strataproxy.plugin.route.RouteStage;
import dev.strataproxy.route.StageRouteEngine;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Adapts live network sessions and metrics to the public plugin player API. */
public final class PluginPlayerService implements PlayerService {
    private final AtomicReference<NettyProxyNetworkServer> server;
    private final ProxyMetrics metrics;
    private final StageRouteEngine routes;
    private final Supplier<Collection<ServerView>> serverViews;

    public PluginPlayerService(AtomicReference<NettyProxyNetworkServer> server, ProxyMetrics metrics) {
        this(server, metrics, null, null);
    }

    public PluginPlayerService(
            AtomicReference<NettyProxyNetworkServer> server,
            ProxyMetrics metrics,
            StageRouteEngine routes,
            Supplier<Collection<ServerView>> serverViews) {
        this.server = server;
        this.metrics = metrics;
        this.routes = routes;
        this.serverViews = serverViews;
    }

    @Override
    public CompletionStage<PlayerTransfer> transfer(String playerName, String targetServer) {
        var current = server.get();
        if (current == null) {
            return CompletableFuture.completedFuture(new PlayerTransfer(false, "server_not_started", playerName, "", targetServer));
        }
        var identity = metrics.findPlayerSession(playerName)
                .map(session -> new PlayerIdentity(session.playerId(), session.connectionId()))
                .orElseGet(() -> new PlayerIdentity(null, ""));
        return current.transferPlayer(playerName, targetServer).thenApply(result -> transfer(result, identity, result.player()));
    }

    @Override
    public CompletionStage<PlayerTransfer> transfer(PlayerIdentity player, String targetServer) {
        var view = find(player).orElse(null);
        if (view == null) {
            return CompletableFuture.completedFuture(new PlayerTransfer(
                    false, "player_not_found", player, "", "", targetServer,
                    PlayerTransfer.TransferStage.REJECTED, false, ""));
        }
        var current = server.get();
        if (current == null) {
            return CompletableFuture.completedFuture(new PlayerTransfer(
                    false, "server_not_started", player, view.name(), view.serverName(), targetServer,
                    PlayerTransfer.TransferStage.REJECTED, false, view.serverName()));
        }
        return current.transferPlayer(player, targetServer).thenApply(result -> transfer(result, player, view.name()));
    }

    @Override
    public CompletionStage<PlayerTransfer> route(PlayerIdentity player, String routeKey) {
        var view = find(player).orElse(null);
        if (view == null) {
            return CompletableFuture.completedFuture(rejected(player, "player_not_found", "", "", ""));
        }
        var current = server.get();
        if (current == null || routes == null || serverViews == null) {
            return CompletableFuture.completedFuture(rejected(player, "route_unavailable", view.name(),
                    view.serverName(), ""));
        }
        var protocolVersion = current.playerProtocol(player).orElse(-1);
        var context = new RouteContext(RouteStage.TRANSFER, routeKey, "", protocolVersion,
                view.remoteAddress(), player, view.name(), view.serverName());
        return routes.evaluate(context).thenCompose(decision -> {
            if (decision.kind() == RouteDecision.Kind.REJECT) {
                return CompletableFuture.completedFuture(rejected(player, "route_rejected", view.name(),
                        view.serverName(), ""));
            }
            var target = decision.kind() == RouteDecision.Kind.SELECT
                    ? decision.serverName()
                    : defaultTransferTarget(context, serverViews.get());
            if (target.isBlank()) {
                return CompletableFuture.completedFuture(rejected(player, "no_route", view.name(),
                        view.serverName(), ""));
            }
            return transfer(player, target);
        });
    }

    private static String defaultTransferTarget(RouteContext context, Collection<ServerView> servers) {
        if (context.routeKey().isBlank()) {
            return "";
        }
        return servers.stream()
                .filter(ServerView::availableForNewConnections)
                .filter(candidate -> !candidate.name().equalsIgnoreCase(context.currentServer()))
                .filter(candidate -> context.protocolVersion() < 0
                        || candidate.protocolRange().minProtocol() <= context.protocolVersion()
                        && context.protocolVersion() <= candidate.protocolRange().maxProtocol())
                .filter(candidate -> candidate.name().equalsIgnoreCase(context.routeKey())
                        || candidate.tags().stream().anyMatch(tag -> tag.equalsIgnoreCase(context.routeKey()))
                        || context.routeKey().equalsIgnoreCase(candidate.metadata().get("route")))
                .min(Comparator.comparing(ServerView::name))
                .map(ServerView::name)
                .orElse("");
    }

    private static PlayerTransfer rejected(PlayerIdentity player, String outcome,
                                           String playerName, String currentServer, String targetServer) {
        return new PlayerTransfer(false, outcome, player, playerName, currentServer, targetServer,
                PlayerTransfer.TransferStage.REJECTED, false, currentServer);
    }

    @Override
    public Optional<PlayerView> find(String playerName) {
        if (playerName == null || playerName.isBlank()) return Optional.empty();
        return metrics.findPlayerSession(playerName).map(PluginPlayerService::view);
    }

    @Override
    public Optional<PlayerView> find(PlayerIdentity player) {
        return metrics.findPlayerSession(player).map(PluginPlayerService::view);
    }

    @Override
    public Collection<PlayerView> onlinePlayers() {
        return metrics.onlinePlayerSessions().stream().map(PluginPlayerService::view).toList();
    }

    private static PlayerView view(ProxyMetrics.PlayerSession session) {
        return new PlayerView(new PlayerIdentity(session.playerId(), session.connectionId()), session.player(), session.server(), session.remoteAddress());
    }

    private static PlayerTransfer transfer(
            dev.strataproxy.network.PlayerTransferResult result,
            PlayerIdentity identity,
            String fallbackPlayerName) {
        var stage = result.success() ? PlayerTransfer.TransferStage.NETWORK_READY
                : "player_session_closed".equals(result.outcome()) ? PlayerTransfer.TransferStage.DISCONNECTED
                : PlayerTransfer.TransferStage.REJECTED;
        var currentServer = result.success() ? result.targetServer() : result.sourceServer();
        return new PlayerTransfer(
                result.success(), result.outcome(), identity,
                result.player().isBlank() ? fallbackPlayerName : result.player(), result.sourceServer(), result.targetServer(),
                stage, !result.success(), currentServer);
    }
}

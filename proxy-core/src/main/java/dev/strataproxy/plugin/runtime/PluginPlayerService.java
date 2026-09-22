package dev.strataproxy.plugin.runtime;

import dev.strataproxy.network.NettyProxyNetworkServer;
import dev.strataproxy.network.ProxyMetrics;
import dev.strataproxy.plugin.service.PlayerIdentity;
import dev.strataproxy.plugin.service.PlayerService;
import dev.strataproxy.plugin.service.PlayerTransfer;
import dev.strataproxy.plugin.service.PlayerView;
import dev.strataproxy.plugin.service.PluginMessageResult;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

/** Adapts live network sessions and metrics to the public plugin player API. */
public final class PluginPlayerService implements PlayerService {
    private final AtomicReference<NettyProxyNetworkServer> server;
    private final ProxyMetrics metrics;

    public PluginPlayerService(AtomicReference<NettyProxyNetworkServer> server, ProxyMetrics metrics) {
        this.server = server;
        this.metrics = metrics;
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
    public CompletionStage<PluginMessageResult> sendPluginMessage(String playerName, String channel, byte[] payload) {
        var current = server.get();
        return current == null
                ? CompletableFuture.completedFuture(PluginMessageResult.failure("server_not_started"))
                : current.sendPluginMessage(playerName, channel, payload);
    }

    @Override
    public CompletionStage<PluginMessageResult> sendPluginMessage(PlayerIdentity player, String channel, byte[] payload) {
        var current = server.get();
        return current == null
                ? CompletableFuture.completedFuture(PluginMessageResult.failure("server_not_started"))
                : current.sendPluginMessage(player, channel, payload);
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

package dev.strataproxy.infrastructure.plugin.runtime;

import dev.strataproxy.infrastructure.minecraft.NettyProxyNetworkServer;
import dev.strataproxy.infrastructure.observability.ProxyMetrics;
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
        return current.transferPlayer(playerName, targetServer).thenApply(result -> new PlayerTransfer(
                result.success(), result.outcome(), new PlayerIdentity(null, ""), result.player(), result.sourceServer(),
                result.targetServer(), result.success() ? PlayerTransfer.TransferStage.NETWORK_READY : PlayerTransfer.TransferStage.REJECTED,
                !result.success(), result.success() ? result.targetServer() : result.sourceServer()));
    }

    @Override
    public CompletionStage<PluginMessageResult> sendPluginMessage(String playerName, String channel, byte[] payload) {
        var current = server.get();
        return current == null
                ? CompletableFuture.completedFuture(PluginMessageResult.failure("server_not_started"))
                : current.sendPluginMessage(playerName, channel, payload);
    }

    @Override
    public Optional<PlayerView> find(String playerName) {
        if (playerName == null || playerName.isBlank()) return Optional.empty();
        var session = metrics.snapshot().playerSessions().get(playerName.trim());
        return session == null ? Optional.empty() : Optional.of(view(session));
    }

    @Override
    public Collection<PlayerView> onlinePlayers() {
        return metrics.snapshot().playerSessions().values().stream().map(PluginPlayerService::view).toList();
    }

    private static PlayerView view(ProxyMetrics.PlayerSession session) {
        return new PlayerView(new PlayerIdentity(session.playerId(), session.connectionId()), session.player(), session.server(), session.remoteAddress());
    }
}

package dev.strataproxy.network;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

final class RelaySessionRegistry {
    private final ConcurrentHashMap<String, RelaySession> sessionsByPlayer = new ConcurrentHashMap<>();

    void register(RelaySession session) {
        var playerName = session.identity().playerName();
        if (playerName.isBlank()) {
            return;
        }
        sessionsByPlayer.put(key(playerName), session);
    }

    void unregister(RelaySession session) {
        var playerName = session.identity().playerName();
        if (!playerName.isBlank()) {
            sessionsByPlayer.remove(key(playerName), session);
        }
    }

    Optional<RelaySession> find(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessionsByPlayer.get(key(playerName)));
    }

    CompletionStage<PlayerTransferResult> transferPlayer(String playerName, String targetServerName) {
        if (playerName == null || playerName.isBlank()) {
            return CompletableFuture.completedFuture(PlayerTransferResult.failure(
                    "invalid_player",
                    playerName,
                    "",
                    targetServerName));
        }
        var session = find(playerName).orElse(null);
        if (session == null) {
            return CompletableFuture.completedFuture(PlayerTransferResult.failure(
                    "player_not_found",
                    playerName.trim(),
                    "",
                    targetServerName));
        }
        var controller = session.replacementController();
        if (controller == null) {
            return CompletableFuture.completedFuture(PlayerTransferResult.failure(
                    "transfer_unavailable",
                    session.identity().playerName(),
                    session.serverName(),
                    targetServerName));
        }
        return controller.replaceBackend(targetServerName);
    }

    CompletionStage<dev.strataproxy.plugin.service.PluginMessageResult> sendPluginMessage(String playerName, String channel, byte[] payload) {
        var session = find(playerName).orElse(null);
        if (session == null) return CompletableFuture.completedFuture(dev.strataproxy.plugin.service.PluginMessageResult.failure("player_not_found"));
        var controller = session.replacementController();
        return controller == null
                ? CompletableFuture.completedFuture(dev.strataproxy.plugin.service.PluginMessageResult.failure("backend_unavailable"))
                : controller.sendPluginMessage(channel, payload);
    }

    private static String key(String playerName) {
        return playerName.trim().toLowerCase(Locale.ROOT);
    }
}

package dev.strataproxy.network;

import dev.strataproxy.plugin.service.PlayerIdentity;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

final class RelaySessionRegistry {
    private final ConcurrentHashMap<String, RelaySession> sessionsByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RelaySession> sessionsByConnection = new ConcurrentHashMap<>();

    void register(RelaySession session) {
        var playerName = session.identity().playerName();
        if (playerName.isBlank()) {
            return;
        }
        sessionsByPlayer.put(key(playerName), session);
        sessionsByConnection.put(session.identity().connectionId(), session);
    }

    void unregister(RelaySession session) {
        var playerName = session.identity().playerName();
        if (!playerName.isBlank()) {
            sessionsByPlayer.remove(key(playerName), session);
        }
        sessionsByConnection.remove(session.identity().connectionId(), session);
    }

    Optional<RelaySession> find(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessionsByPlayer.get(key(playerName)));
    }

    Optional<RelaySession> find(PlayerIdentity identity) {
        if (identity == null || identity.connectionId().isBlank()) {
            return Optional.empty();
        }
        var session = sessionsByConnection.get(identity.connectionId());
        if (session == null || (identity.uuid() != null && !identity.uuid().equals(session.identity().playerId()))) {
            return Optional.empty();
        }
        return Optional.of(session);
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

    CompletionStage<PlayerTransferResult> transferPlayer(PlayerIdentity identity, String targetServerName) {
        var session = find(identity).orElse(null);
        if (session == null) {
            return CompletableFuture.completedFuture(PlayerTransferResult.failure("player_not_found", "", "", targetServerName));
        }
        var controller = session.replacementController();
        if (controller == null) {
            return CompletableFuture.completedFuture(PlayerTransferResult.failure(
                    "transfer_unavailable", session.identity().playerName(), session.serverName(), targetServerName));
        }
        return controller.replaceBackend(targetServerName);
    }

    private static String key(String playerName) {
        return playerName.trim().toLowerCase(Locale.ROOT);
    }
}

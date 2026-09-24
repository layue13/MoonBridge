package dev.strataproxy.plugin.service;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Read and control surface for online players.
 */
public interface PlayerService {
    /**
     * Requests a transfer of an online player to another registered backend.
     *
     * @param playerName player name
     * @param targetServer target backend name
     * @return asynchronous transfer result
     */
    CompletionStage<PlayerTransfer> transfer(String playerName, String targetServer);

    /**
     * Requests a transfer for one exact proxy connection.
     *
     * <p>Use an identity obtained from {@link PlayerView#identity()} when an
     * asynchronous caller must not act on a later connection that reused the
     * same player name.</p>
     *
     * @param player exact player connection identity
     * @param targetServer target backend name
     * @return asynchronous transfer result
     */
    default CompletionStage<PlayerTransfer> transfer(PlayerIdentity player, String targetServer) {
        return java.util.concurrent.CompletableFuture.completedFuture(new PlayerTransfer(
                false, "identity_transfer_unsupported", player, "", "", targetServer,
                PlayerTransfer.TransferStage.REJECTED, false, ""));
    }

    /**
 * Provides find.
 *
     * @param playerName player name
     * @return player view when the player is currently online
     */
    Optional<PlayerView> find(String playerName);

    /** Finds an online player by the UUID and connection identity returned by a prior view. */
    default Optional<PlayerView> find(PlayerIdentity player) {
        if (player == null || player.connectionId().isBlank()) {
            return Optional.empty();
        }
        return onlinePlayers().stream().filter(view -> view.identity().equals(player)).findFirst();
    }

    /**
 * Provides online players.
 *
     * @return snapshot of online players known to the proxy
     */
    Collection<PlayerView> onlinePlayers();
}

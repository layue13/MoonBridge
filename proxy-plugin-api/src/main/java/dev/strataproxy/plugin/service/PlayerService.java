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
     * Sends an opaque plugin-message payload to the player's current backend.
     * Completion means Netty accepted the packet for writing, not that Bukkit processed it.
     */
    default CompletionStage<PluginMessageResult> sendPluginMessage(String playerName, String channel, byte[] payload) {
        return java.util.concurrent.CompletableFuture.completedFuture(PluginMessageResult.failure("unsupported"));
    }

    /**
 * Provides find.
 *
     * @param playerName player name
     * @return player view when the player is currently online
     */
    Optional<PlayerView> find(String playerName);

    /**
 * Provides online players.
 *
     * @return snapshot of online players known to the proxy
     */
    Collection<PlayerView> onlinePlayers();
}

package dev.strataproxy.plugin.event;

import dev.strataproxy.plugin.service.PlayerIdentity;
import dev.strataproxy.plugin.service.PlayerTransfer;

/**
 * Event emitted when a player transfer attempt finishes.
 *
 * @param playerName player being transferred
 * @param sourceServer server the player left
 * @param targetServer requested target server
 * @param success whether the transfer completed
 * @param outcome diagnostic outcome string
 */
public record PlayerTransferEvent(PlayerIdentity player, String playerName, String sourceServer, String targetServer,
                                  boolean success, String outcome, PlayerTransfer.TransferStage stage,
                                  boolean retryable, String currentServer)
        implements ProxyEvent {
    public PlayerTransferEvent(String playerName, String sourceServer, String targetServer, boolean success, String outcome) {
        this(new PlayerIdentity(null, ""), playerName, sourceServer, targetServer, success, outcome,
                success ? PlayerTransfer.TransferStage.NETWORK_READY : PlayerTransfer.TransferStage.REJECTED,
                !success, success ? targetServer : sourceServer);
    }
}

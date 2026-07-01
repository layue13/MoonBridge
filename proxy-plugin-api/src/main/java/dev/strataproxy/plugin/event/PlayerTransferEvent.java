package dev.strataproxy.plugin.event;

/**
 * Event emitted when a player transfer attempt finishes.
 *
 * @param playerName player being transferred
 * @param sourceServer server the player left
 * @param targetServer requested target server
 * @param success whether the transfer completed
 * @param outcome diagnostic outcome string
 */
public record PlayerTransferEvent(String playerName, String sourceServer, String targetServer, boolean success, String outcome)
        implements ProxyEvent {
}

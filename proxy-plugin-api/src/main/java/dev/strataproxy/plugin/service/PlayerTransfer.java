package dev.strataproxy.plugin.service;

/**
 * Result of a plugin-requested player transfer.
 *
 * @param success whether the transfer completed
 * @param outcome diagnostic outcome string
 * @param playerName player that was transferred
 * @param sourceServer backend the player started on
 * @param targetServer requested target backend
 */
public record PlayerTransfer(boolean success, String outcome, String playerName, String sourceServer, String targetServer) {
}

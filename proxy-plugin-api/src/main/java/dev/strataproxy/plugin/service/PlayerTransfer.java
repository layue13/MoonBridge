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
public record PlayerTransfer(boolean success, String outcome, PlayerIdentity player, String playerName,
                             String sourceServer, String targetServer, TransferStage stage,
                             boolean retryable, String currentServer) {
    public PlayerTransfer(boolean success, String outcome, String playerName, String sourceServer, String targetServer) {
        this(success, outcome, new PlayerIdentity(null, ""), playerName, sourceServer, targetServer,
                success ? TransferStage.NETWORK_READY : TransferStage.REJECTED, !success, success ? targetServer : sourceServer);
    }

    /** The furthest proxy-level milestone reached; backend application readiness is not implied. */
    public enum TransferStage { REJECTED, CONNECTING, NETWORK_READY, DISCONNECTED }
}

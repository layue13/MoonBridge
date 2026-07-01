package dev.strataproxy.admin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Admin-facing service for requesting player transfers.
 */
public interface PlayerTransferService {
    /**
     * Requests that a player move to another backend server.
     *
     * @param playerName player to transfer
     * @param targetServer target backend name
     * @return asynchronous transfer result
     */
    CompletionStage<TransferResult> transferPlayer(String playerName, String targetServer);

    /**
     * @return service implementation that reports transfer support as unavailable
     */
    static PlayerTransferService unavailable() {
        return (playerName, targetServer) -> CompletableFuture.completedFuture(TransferResult.failure(
                "transfer_unavailable",
                playerName,
                "",
                targetServer));
    }

    /**
     * Result of an admin-requested transfer.
     *
     * @param success whether the transfer completed
     * @param outcome stable outcome or failure reason
     * @param player player name
     * @param sourceServer source backend name
     * @param targetServer requested target backend name
     */
    record TransferResult(
            boolean success,
            String outcome,
            String player,
            String sourceServer,
            String targetServer) {
        /**
         * @param player player name
         * @param sourceServer source backend name
         * @param targetServer target backend name
         * @return successful transfer result
         */
        public static TransferResult success(String player, String sourceServer, String targetServer) {
            return new TransferResult(true, "success", value(player), value(sourceServer), value(targetServer));
        }

        /**
         * @param outcome failure outcome
         * @param player player name
         * @param sourceServer source backend name
         * @param targetServer target backend name
         * @return failed transfer result
         */
        public static TransferResult failure(String outcome, String player, String sourceServer, String targetServer) {
            return new TransferResult(false, value(outcome), value(player), value(sourceServer), value(targetServer));
        }

        private static String value(String value) {
            return value == null ? "" : value;
        }
    }
}

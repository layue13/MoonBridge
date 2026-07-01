package dev.strataproxy.admin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public interface PlayerTransferService {
    CompletionStage<TransferResult> transferPlayer(String playerName, String targetServer);

    static PlayerTransferService unavailable() {
        return (playerName, targetServer) -> CompletableFuture.completedFuture(TransferResult.failure(
                "transfer_unavailable",
                playerName,
                "",
                targetServer));
    }

    record TransferResult(
            boolean success,
            String outcome,
            String player,
            String sourceServer,
            String targetServer) {
        public static TransferResult success(String player, String sourceServer, String targetServer) {
            return new TransferResult(true, "success", value(player), value(sourceServer), value(targetServer));
        }

        public static TransferResult failure(String outcome, String player, String sourceServer, String targetServer) {
            return new TransferResult(false, value(outcome), value(player), value(sourceServer), value(targetServer));
        }

        private static String value(String value) {
            return value == null ? "" : value;
        }
    }
}

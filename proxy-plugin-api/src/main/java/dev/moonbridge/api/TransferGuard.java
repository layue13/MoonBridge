package dev.moonbridge.api;

import java.util.concurrent.CompletionStage;

/** Coordinates application state that must move with a player backend transfer. */
public interface TransferGuard {
    /**
     * Prepares the source before MoonBridge opens the replacement backend connection. Returning
     * ALLOW means source handoff is complete. This callback may have side effects even when it
     * rejects, fails, or times out; MoonBridge calls {@link #failed} unless cutover succeeds.
     */
    CompletionStage<TransferGuardDecision> prepare(TransferContext transfer);

    /**
     * Resolves a transfer that did not complete. Return SOURCE_RESTORED only after the exact
     * source has been safely restored. The callback must be idempotent by transferId because a
     * prepare acknowledgement may be lost while the source operation is still finishing.
     */
    CompletionStage<TransferFailureDisposition> failed(TransferContext transfer, String reason);

    /** Called after network cutover succeeds, for plugin-owned cleanup of transfer state. */
    default CompletionStage<Void> completed(TransferContext transfer) {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }
}

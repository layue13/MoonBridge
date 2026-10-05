package dev.moonbridge.core.session;

import dev.moonbridge.api.TransferResult;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;

/** A transfer request waiting for the initial PLAY or Forge handshake to finish. */
final class PendingTransfer {
    final String backendName;
    final CompletableFuture<TransferResult> result;
    ScheduledFuture<?> deadline;

    PendingTransfer(String backendName, CompletableFuture<TransferResult> result) {
        this.backendName = backendName;
        this.result = result;
    }
}

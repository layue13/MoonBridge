package dev.moonbridge.core.session.transfer;

import dev.moonbridge.api.TransferResult;
import dev.moonbridge.api.event.TransferContext;
import dev.moonbridge.core.backend.BackendView;
import dev.moonbridge.core.event.TransferPreparation;
import dev.moonbridge.core.relay.RawRelay;
import io.netty.channel.Channel;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;

/** State of one replacement-backend attempt; owned by the session event loop. */
final class TransferAttempt {
    final BackendView target;
    final CompletableFuture<TransferResult> result;
    final RawRelay.Link oldRelay;
    TransferCandidate candidate;
    TransferFrameHandler.State frameState;
    TransferFrameBuffer clientBuffer;
    TransferFrameBuffer oldBackendBuffer;
    Channel channel;
    boolean pauseInProgress;
    boolean paused;
    boolean detached;
    boolean finished;
    String failureReason;
    ScheduledFuture<?> cutoverDeadline;
    TransferPreparation preparation;
    TransferContext context;
    CompletableFuture<?> coordination;
    ScheduledFuture<?> totalDeadline;
    boolean sourceReleased;

    TransferAttempt(BackendView target, CompletableFuture<TransferResult> result, RawRelay.Link oldRelay) {
        this.target = target;
        this.result = result;
        this.oldRelay = oldRelay;
    }
}

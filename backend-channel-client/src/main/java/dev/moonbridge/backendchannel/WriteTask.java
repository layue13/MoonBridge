package dev.moonbridge.backendchannel;

import dev.moonbridge.messaging.Message;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;

final class WriteTask {
    final byte[] frame;
    final long deadlineNanos;
    final PendingOperation pending;
    final CompletableFuture<Void> written;
    final Message message;
    final long operationId;
    final long originalTimeoutMillis;
    volatile boolean started;
    volatile boolean writeInProgress;
    volatile boolean writeCompleted;
    boolean accounted;
    volatile ScheduledFuture<?> expiryTask;
    long expiryGeneration;
    WriteTask(byte[] frame, long deadlineNanos, PendingOperation pending, CompletableFuture<Void> written) {
        this(frame, deadlineNanos, pending, written, null, 0L, 0L);
    }
    WriteTask(byte[] frame, long deadlineNanos, PendingOperation pending, CompletableFuture<Void> written,
              Message message, long operationId, long originalTimeoutMillis) {
        this.frame = frame;
        this.deadlineNanos = deadlineNanos;
        this.pending = pending;
        this.written = written;
        this.message = message;
        this.operationId = operationId;
        this.originalTimeoutMillis = originalTimeoutMillis;
    }
}

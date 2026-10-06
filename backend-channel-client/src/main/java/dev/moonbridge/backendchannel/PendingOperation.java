package dev.moonbridge.backendchannel;

import dev.moonbridge.messaging.Message;
import dev.moonbridge.messaging.MessageKind;
import dev.moonbridge.messaging.MessagingException;
import dev.moonbridge.messaging.protocol.MessageCodec;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static dev.moonbridge.backendchannel.Failures.*;

final class PendingOperation {
    final ClientSession owner;
    final long operationId;
    final MessageCodec.Response.Type expected;
    final Message message;
    final long deadlineNanos;
    final CompletableFuture<MessageCodec.Response> future = new CompletableFuture<MessageCodec.Response>() {
        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelled) cancelPending();
            return cancelled;
        }
    };
    final AtomicBoolean done = new AtomicBoolean();
    volatile ScheduledFuture<?> timeoutTask;
    volatile WriteTask writeTask;

    PendingOperation(ClientSession owner, long operationId, MessageCodec.Response.Type expected, Message message, long deadlineNanos) {
        this.owner = owner;
        this.operationId = operationId;
        this.expected = expected;
        this.message = message;
        this.deadlineNanos = deadlineNanos;
    }

    void startTimeout() {
        try {
            timeoutTask = owner.timer.schedule(new Runnable() {
                @Override public void run() {
                    fail(new MessagingException(MessagingException.Code.TIMED_OUT, "backend message timed out"));
                }
            }, Math.max(0L, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException stopped) {
            fail(new MessagingException(MessagingException.Code.CLOSED, "backend messaging is closed", stopped));
        }
        if (done.get() && timeoutTask != null) timeoutTask.cancel(false);
    }

    boolean isDone() { return done.get(); }

    void accept(MessageCodec.Response response) {
        if (done.get()) return;
        if (response.type == MessageCodec.Response.Type.ERROR) {
            fail(new MessagingException(response.errorCode, response.detail));
            return;
        }
        if (response.type != expected) {
            fail(new MessagingException(MessagingException.Code.PROTOCOL_ERROR, "unexpected response type"));
            return;
        }
        if (expected == MessageCodec.Response.Type.REPLY && !validReply(message, response.message)) {
            fail(new MessagingException(MessagingException.Code.PROTOCOL_ERROR, "reply does not match request"));
            return;
        }
        if (expected == MessageCodec.Response.Type.PUBLISH
                && !message.id().equals(response.publishResult.messageId())) {
            fail(new MessagingException(MessagingException.Code.PROTOCOL_ERROR, "publish receipt has the wrong message ID"));
            return;
        }
        finish(response, null);
    }

    void fail(Throwable failure) { finish(null, failure); }

    void finish(MessageCodec.Response response, Throwable failure) {
        WriteTask currentWrite = writeTask;
        boolean blockedWriteExpired = failure instanceof MessagingException
                && ((MessagingException) failure).code() == MessagingException.Code.TIMED_OUT
                && currentWrite != null && currentWrite.writeInProgress && !currentWrite.writeCompleted;
        if (!cleanup()) return;
        if (blockedWriteExpired) owner.close(failure);
        if (failure == null) future.complete(response);
        else future.completeExceptionally(failure);
    }

    private void cancelPending() { cleanup(); }

    private boolean cleanup() {
        synchronized (owner.writeLock) {
            if (!done.compareAndSet(false, true)) return false;
            if (owner.pending.remove(operationId, this)) owner.requestSlots.release();
            ScheduledFuture<?> timeout = timeoutTask;
            if (timeout != null) timeout.cancel(false);
            WriteTask queued = writeTask;
            if (queued != null) owner.remove(queued);
        }
        return true;
    }

private static boolean validReply(Message request, Message reply) {
    return reply != null && reply.kind() == MessageKind.REPLY
            && request.channel().equals(reply.channel())
            && request.id().equals(reply.replyTo())
            && request.target().equals(reply.source())
            && request.source().equals(reply.target());
}
}

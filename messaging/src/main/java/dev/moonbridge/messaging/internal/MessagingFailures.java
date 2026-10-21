package dev.moonbridge.messaging.internal;

import dev.moonbridge.messaging.MessagingException;
import dev.moonbridge.messaging.SendReceipt;
import dev.moonbridge.messaging.SendResult;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Failure translation shared by the local messaging classes. */
final class MessagingFailures {
    private MessagingFailures() { }

    static long timeoutMillis(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        final long millis;
        try {
            millis = timeout.toMillis();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("timeout is too large", overflow);
        }
        if (millis <= 0 || millis > LocalMessaging.MAX_REQUEST_TIMEOUT.toMillis()) {
            throw new IllegalArgumentException("timeout must be positive and at most 60 seconds");
        }
        return millis;
    }

    static MessagingException handlerFailure(Throwable failure) {
        return new MessagingException(MessagingException.Code.HANDLER_FAILED,
                "message handler failed", failure);
    }

    static SendReceipt sendReceiptForFailure(UUID messageId, Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof MessagingException) {
            MessagingException messagingFailure = (MessagingException) cause;
            switch (messagingFailure.code()) {
                case TIMED_OUT: return new SendReceipt(messageId, SendResult.TIMED_OUT);
                case BACKPRESSURED: return new SendReceipt(messageId, SendResult.BACKPRESSURED);
                case NOT_CONNECTED: return new SendReceipt(messageId, SendResult.NOT_CONNECTED);
                case NO_HANDLER: return new SendReceipt(messageId, SendResult.NO_SUBSCRIBER);
                case REJECTED: return new SendReceipt(messageId, SendResult.REJECTED);
                case HANDLER_FAILED:
                case PROTOCOL_ERROR: return new SendReceipt(messageId, SendResult.FAILED);
                case CLOSED: throw messagingFailure;
                default: return new SendReceipt(messageId, SendResult.FAILED);
            }
        }
        return new SendReceipt(messageId, SendResult.FAILED);
    }

    static Throwable unwrap(Throwable failure) {
        if (failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }

    static <T> CompletionStage<T> failed(Throwable failure) {
        CompletableFuture<T> future = new CompletableFuture<T>();
        future.completeExceptionally(failure);
        return future;
    }
}

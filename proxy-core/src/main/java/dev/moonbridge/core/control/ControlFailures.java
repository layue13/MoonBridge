package dev.moonbridge.core.control;

import dev.moonbridge.messaging.MessagingException;
import dev.moonbridge.messaging.SendResult;
import dev.moonbridge.messaging.protocol.MessageCodec;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import static dev.moonbridge.core.control.ControlFailures.*;
import static dev.moonbridge.core.control.ControlMessages.*;
import static dev.moonbridge.core.control.ControlProtocol.*;

/** Translation between thrown failures, messaging errors, send results and error frames. */
final class ControlFailures {
    private ControlFailures() { }

    static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    static MessagingException asMessagingException(Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof MessagingException messagingFailure) return messagingFailure;
        return new MessagingException(MessagingException.Code.HANDLER_FAILED,
                "message delivery failed", cause);
    }

    static SendResult sendFailure(Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof MessagingException messagingFailure) {
            return switch (messagingFailure.code()) {
                case BACKPRESSURED -> SendResult.BACKPRESSURED;
                case NOT_CONNECTED, CLOSED -> SendResult.NOT_CONNECTED;
                case TIMED_OUT -> SendResult.TIMED_OUT;
                case NO_HANDLER -> SendResult.NO_SUBSCRIBER;
                case REJECTED -> SendResult.REJECTED;
                case HANDLER_FAILED, PROTOCOL_ERROR -> SendResult.FAILED;
            };
        }
        return SendResult.FAILED;
    }

    static String safeDetail(Throwable failure) {
        String detail = failure.getMessage();
        return detail == null ? failure.getClass().getSimpleName() : detail;
    }

    static <T> CompletionStage<T> failed(Throwable failure) {
        return CompletableFuture.failedFuture(failure);
    }

    static byte[] errorFrame(long operationId, Throwable failure) {
        try { return MessageCodec.error(operationId, MessagingException.Code.PROTOCOL_ERROR, safeDetail(failure)); }
        catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    static <T, R> CompletionStage<R> mapCancellable(CompletionStage<T> source,
                                                             java.util.function.Function<T, R> mapper) {
        CompletableFuture<T> sourceFuture = source.toCompletableFuture();
        CompletableFuture<R> mapped = new CompletableFuture<>();
        source.whenComplete((value, failure) -> {
            if (failure != null) mapped.completeExceptionally(unwrap(failure));
            else {
                try { mapped.complete(mapper.apply(value)); }
                catch (Throwable mappingFailure) { mapped.completeExceptionally(mappingFailure); }
            }
        });
        mapped.whenComplete((ignored, failure) -> {
            if (mapped.isCancelled()) sourceFuture.cancel(false);
        });
        return mapped;
    }
}

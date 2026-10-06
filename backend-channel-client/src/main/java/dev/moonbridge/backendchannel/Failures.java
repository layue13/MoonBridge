package dev.moonbridge.backendchannel;

import dev.moonbridge.messaging.MessagingException;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Failure construction and translation shared by the backend channel classes. */
final class Failures {
    private Failures() { }

    static String safeDetail(Throwable failure) {
        String detail = failure == null ? "message failed" : failure.getMessage();
        if (detail == null || detail.isEmpty()) detail = failure == null ? "message failed" : failure.getClass().getSimpleName();
        byte[] bytes = detail.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= 1024) return detail;
        int end = 1024;
        while (end > 0 && (bytes[end] & 0xc0) == 0x80) end--;
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    static MessagingException notConnected() {
        return new MessagingException(MessagingException.Code.NOT_CONNECTED, "backend channel is disconnected");
    }

    static Throwable unwrap(Throwable failure) {
        if (failure instanceof CompletionException && failure.getCause() != null) return failure.getCause();
        if (failure instanceof ExecutionException && failure.getCause() != null) return failure.getCause();
        return failure;
    }

    static <T> CompletableFuture<T> failed(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<T>();
        future.completeExceptionally(error);
        return future;
    }
}

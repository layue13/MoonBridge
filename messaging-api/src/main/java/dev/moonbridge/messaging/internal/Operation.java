package dev.moonbridge.messaging.internal;

import dev.moonbridge.messaging.MessagingException;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** One bounded messaging operation: a timeout, an optional upstream stage to cancel, and a single completion. */
final class Operation<T> {
    private final OperationHost host;
    final Set<Operation<?>> scopeOperations;
    final Runnable release;
    private final Function<Throwable, T> failureMapper;
    final OperationFuture<T> future = new OperationFuture<T>(this);
    private final AtomicBoolean done = new AtomicBoolean();
    private volatile ScheduledFuture<?> timer;
    private volatile CompletionStage<?> underlying;

    Operation(OperationHost host, Set<Operation<?>> scopeOperations, Runnable release) {
        this(host, scopeOperations, release, null);
    }

    Operation(OperationHost host, Set<Operation<?>> scopeOperations, Runnable release,
              Function<Throwable, T> failureMapper) {
        this.host = host;
        this.scopeOperations = scopeOperations;
        this.release = release;
        this.failureMapper = failureMapper;
    }

    boolean isDone() { return done.get(); }

    boolean armTimeout(long millis) {
        if (done.get()) return false;
        try {
            ScheduledFuture<?> scheduled = host.scheduler().schedule(new Runnable() {
                @Override public void run() {
                    fail(new MessagingException(MessagingException.Code.TIMED_OUT,
                            "message request timed out"));
                }
            }, millis, TimeUnit.MILLISECONDS);
            timer = scheduled;
            if (done.get()) {
                scheduled.cancel(false);
                return false;
            }
            return true;
        } catch (RejectedExecutionException rejected) {
            fail(new MessagingException(MessagingException.Code.CLOSED,
                    "request timer is unavailable", rejected));
            return false;
        }
    }

    void succeed(T value) { finish(value, null); }

    void fail(Throwable failure) { finish(null, failure); }

    void failLocked(Throwable failure) {
        if (!done.compareAndSet(false, true)) return;
        // This operation was rejected before admission and its future has not escaped the API call.
        completeFailureNow(failure);
    }

    void setCancellation(CompletionStage<?> stage) {
        underlying = stage;
        if (done.get()) cancelUnderlying();
    }

    void cancel() {
        if (!done.compareAndSet(false, true)) return;
        ScheduledFuture<?> scheduled = timer;
        if (scheduled != null) scheduled.cancel(false);
        cancelUnderlying();
        host.finished(this);
    }

    private void finish(T value, Throwable failure) {
        if (!done.compareAndSet(false, true)) return;
        ScheduledFuture<?> scheduled = timer;
        if (scheduled != null) scheduled.cancel(false);
        if (failure instanceof MessagingException) {
            MessagingException.Code code = ((MessagingException) failure).code();
            if (code == MessagingException.Code.TIMED_OUT || code == MessagingException.Code.CLOSED) {
                cancelUnderlying();
            }
        }
        if (failure == null) {
            host.complete(this, new Runnable() { @Override public void run() { future.complete(value); } });
        } else {
            host.complete(this, new Runnable() {
                @Override public void run() { completeFailureNow(failure); }
            });
        }
    }

    private void completeFailureNow(Throwable failure) {
        if (failureMapper == null) {
            future.completeExceptionally(failure);
            return;
        }
        try { future.complete(failureMapper.apply(failure)); }
        catch (Throwable mappingFailure) { future.completeExceptionally(mappingFailure); }
    }

    private void cancelUnderlying() {
        CompletionStage<?> stage = underlying;
        if (stage != null) {
            try { stage.toCompletableFuture().cancel(true); } catch (Throwable ignored) { }
        }
    }

    static final class OperationFuture<T> extends CompletableFuture<T> {
        private final Operation<T> operation;

        OperationFuture(Operation<T> operation) { this.operation = operation; }

        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelled) operation.cancel();
            return cancelled;
        }
    }
}

package dev.strataproxy.core.plugin;

import dev.strataproxy.api.AccessDecision;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Runs ordered plugin access checks without blocking the caller's event loop. */
final class AccessChecks implements AutoCloseable {
    private final Duration timeout;
    private final int maxPending;
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService timer;
    private final Set<PendingAccess<?>> active = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private boolean closed;

    AccessChecks(Duration timeout, int workerCount, int queueCapacity, int maxPending,
                 ScheduledExecutorService timer, ThreadFactory threadFactory) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.timer = Objects.requireNonNull(timer, "timer");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("accessTimeout must be positive");
        }
        if (workerCount < 1 || queueCapacity < 1 || maxPending < 1) {
            throw new IllegalArgumentException("access executor and pending limits must be positive");
        }
        this.maxPending = maxPending;
        this.workers = new ThreadPoolExecutor(workerCount, workerCount, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), Objects.requireNonNull(threadFactory, "threadFactory"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    <T> CompletionStage<AccessDecision> check(
            List<? extends Function<T, CompletionStage<AccessDecision>>> checks, T request) {
        Objects.requireNonNull(checks, "checks");
        Objects.requireNonNull(request, "request");
        List<? extends Function<T, CompletionStage<AccessDecision>>> ordered = List.copyOf(checks);

        PendingAccess<T> pending;
        synchronized (this) {
            if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Plugin host is closed"));
            if (ordered.isEmpty()) return CompletableFuture.completedFuture(AccessDecision.allow());
            if (active.size() >= maxPending) {
                return CompletableFuture.failedFuture(new PluginOverloadedException(
                        new RejectedExecutionException("pending access-check limit reached")));
            }
            pending = new PendingAccess<>(ordered, request);
            active.add(pending);
            try {
                pending.deadline = timer.schedule(() -> pending.fail(
                                new TimeoutException("Plugin access checks exceeded " + timeout)),
                        timeout.toNanos(), TimeUnit.NANOSECONDS);
            } catch (RuntimeException failure) {
                active.remove(pending);
                return CompletableFuture.failedFuture(failure);
            }
        }
        pending.result.whenComplete((ignored, failure) -> pending.cleanup());
        pending.submit(0);
        return pending.result;
    }

    @Override
    public void close() {
        List<PendingAccess<?>> pending;
        synchronized (this) {
            if (closed) return;
            closed = true;
            pending = List.copyOf(active);
        }
        for (PendingAccess<?> request : pending) {
            request.fail(new IllegalStateException("Plugin host closed during access check"));
        }
        workers.shutdownNow();
    }

    private final class PendingAccess<T> {
        private final List<? extends Function<T, CompletionStage<AccessDecision>>> checks;
        private final T request;
        private final CompletableFuture<AccessDecision> result = new CompletableFuture<>();
        private final AtomicBoolean settled = new AtomicBoolean();
        private final AtomicReference<FutureTask<Void>> queuedTask = new AtomicReference<>();
        private final AtomicReference<CompletableFuture<AccessDecision>> pluginStage = new AtomicReference<>();
        private final AtomicReference<Thread> runningThread = new AtomicReference<>();
        private volatile ScheduledFuture<?> deadline;

        private PendingAccess(List<? extends Function<T, CompletionStage<AccessDecision>>> checks, T request) {
            this.checks = checks;
            this.request = request;
        }

        private void submit(int index) {
            if (settled.get()) return;
            if (index >= checks.size()) {
                finish(AccessDecision.allow());
                return;
            }
            FutureTask<Void> task = new FutureTask<>(() -> {
                Thread current = Thread.currentThread();
                runningThread.set(current);
                try {
                    if (settled.get()) return null;
                    try {
                        CompletionStage<AccessDecision> stage = Objects.requireNonNull(
                                checks.get(index).apply(request), "access check stage");
                        CompletableFuture<AccessDecision> cancellable = stage.toCompletableFuture();
                        pluginStage.set(cancellable);
                        if (settled.get()) {
                            cancellable.cancel(false);
                            return null;
                        }
                        stage.whenComplete((decision, failure) -> {
                            pluginStage.compareAndSet(cancellable, null);
                            if (failure != null) fail(failure);
                            else if (decision == null) fail(new IllegalStateException("Access check returned null"));
                            else if (decision instanceof AccessDecision.Denied) finish(decision);
                            else if (decision instanceof AccessDecision.Allowed) submit(index + 1);
                            else fail(new IllegalStateException("Access check returned an unsupported decision"));
                        });
                    } catch (Throwable failure) {
                        fail(failure);
                    }
                    return null;
                } finally {
                    runningThread.compareAndSet(current, null);
                }
            });
            queuedTask.set(task);
            if (settled.get()) {
                queuedTask.compareAndSet(task, null);
                task.cancel(false);
                return;
            }
            try {
                workers.execute(task);
                // Completion may race the interval between publishing the task and enqueueing it.
                // Remove a cancelled FutureTask after execute so it cannot occupy queue capacity.
                if (settled.get() && workers.remove(task)) task.cancel(false);
            } catch (RejectedExecutionException failure) {
                queuedTask.compareAndSet(task, null);
                fail(new PluginOverloadedException(failure));
            }
        }

        private void finish(AccessDecision decision) {
            if (settled.compareAndSet(false, true)) result.complete(decision);
        }

        private void fail(Throwable failure) {
            if (settled.compareAndSet(false, true)) result.completeExceptionally(failure);
        }

        private void cleanup() {
            settled.set(true);
            ScheduledFuture<?> timeoutTask = deadline;
            if (timeoutTask != null) timeoutTask.cancel(false);
            synchronized (AccessChecks.this) {
                active.remove(this);
            }
            FutureTask<Void> task = queuedTask.getAndSet(null);
            if (task != null && !task.isDone()) {
                workers.remove(task);
                boolean interrupt = (result.isCancelled() || result.isCompletedExceptionally())
                        && runningThread.get() != Thread.currentThread();
                task.cancel(interrupt);
            }
            CompletableFuture<AccessDecision> stage = pluginStage.getAndSet(null);
            if (stage != null && !stage.isDone()) stage.cancel(false);
        }
    }
}

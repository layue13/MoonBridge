package dev.strataproxy.core.plugin;

import dev.strataproxy.api.event.Event;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashSet;
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
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Runs typed listener chains with bounded work and no plugin callbacks on the publisher thread. */
final class AsyncEventDispatcher implements AutoCloseable {
    private static final ThreadFactory CANCELLATIONS = Thread.ofVirtual()
            .name("strataproxy-plugin-event-cancel-", 0).factory();
    private final Duration timeout;
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService timer;
    private final ArrayDeque<PendingEvent<?>> waiting = new ArrayDeque<>();
    private final Set<PendingEvent<?>> pending = new HashSet<>();
    private boolean serialEventActive;
    private boolean closed;

    AsyncEventDispatcher(Duration timeout, int workerCount, int workerQueueCapacity,
                         ScheduledExecutorService timer, ThreadFactory threadFactory) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.timer = Objects.requireNonNull(timer, "timer");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("eventTimeout must be positive");
        if (workerCount < 1 || workerQueueCapacity < 1) {
            throw new IllegalArgumentException("event dispatcher limits must be positive");
        }
        workers = new ThreadPoolExecutor(workerCount, workerCount, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(workerQueueCapacity), Objects.requireNonNull(threadFactory, "threadFactory"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    <R> CompletionStage<R> dispatch(Event<R> event, List<? extends EventHandler<R>> listeners,
                                    EventPolicy<R> policy) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(listeners, "listeners");
        Objects.requireNonNull(policy, "policy");
        final PendingEvent<R> request;
        final boolean start;
        synchronized (this) {
            if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Plugin host is closed"));
            if (listeners.isEmpty()) return CompletableFuture.completedFuture(policy.defaultResult().get());
            if (pending.size() >= policy.maxPending()) {
                return CompletableFuture.failedFuture(new PluginOverloadedException(
                        new RejectedExecutionException("pending event limit reached")));
            }
            // The host supplies a frozen list, so dispatch does not copy or map its subscriptions.
            request = new PendingEvent<>(event, listeners, policy);
            pending.add(request);
            try {
                request.deadline = timer.schedule(() -> request.fail(new TimeoutException(
                        "Plugin event exceeded " + timeout)), timeout.toNanos(), TimeUnit.NANOSECONDS);
            } catch (RuntimeException failure) {
                pending.remove(request);
                return CompletableFuture.failedFuture(failure);
            }
            start = !policy.serializeEvents() || !serialEventActive;
            if (policy.serializeEvents() && start) {
                serialEventActive = true;
                request.serialActive = true;
            }
            if (!start) waiting.addLast(request);
        }
        request.result.whenComplete((ignored, failure) -> request.cleanup());
        if (start) request.submit(() -> request.invoke(0));
        return request.result;
    }

    @Override
    public void close() {
        List<PendingEvent<?>> requests;
        synchronized (this) {
            if (closed) return;
            closed = true;
            requests = List.copyOf(pending);
            waiting.clear();
        }
        requests.forEach(request -> request.fail(new IllegalStateException("Plugin host closed during event delivery")));
        workers.shutdownNow();
    }

    interface EventHandler<R> {
        boolean active();
        CompletionStage<R> handle(Event<?> event) throws Throwable;
    }

    record EventPolicy<R>(Supplier<R> defaultResult, Predicate<R> validResult, Predicate<R> terminalResult,
                          boolean isolateListenerFailures, BiConsumer<Event<?>, Throwable> isolatedFailure,
                          int maxPending, boolean serializeEvents) {
        EventPolicy {
            Objects.requireNonNull(defaultResult, "defaultResult");
            Objects.requireNonNull(validResult, "validResult");
            Objects.requireNonNull(terminalResult, "terminalResult");
            Objects.requireNonNull(isolatedFailure, "isolatedFailure");
            if (maxPending < 1) throw new IllegalArgumentException("maxPending must be positive");
        }
    }

    private static void cancelListener(CompletableFuture<?> stage) {
        if (stage == null || stage.isDone()) return;
        // cancel() may execute arbitrary plugin continuations inline. This exceptional-path task
        // must also survive worker shutdown and must not block I/O or the shared deadline timer.
        CANCELLATIONS.newThread(() -> stage.cancel(false)).start();
    }

    private final class PendingEvent<R> {
        private final Event<R> event;
        private final List<? extends EventHandler<R>> listeners;
        private final EventPolicy<R> policy;
        private final CompletableFuture<R> result = new CompletableFuture<>();
        private final AtomicBoolean settled = new AtomicBoolean();
        private final AtomicReference<FutureTask<Void>> invocation = new AtomicReference<>();
        private final AtomicReference<CompletableFuture<R>> listenerFuture = new AtomicReference<>();
        private final AtomicReference<Thread> runningThread = new AtomicReference<>();
        private volatile ScheduledFuture<?> deadline;
        private boolean serialActive;

        private PendingEvent(Event<R> event, List<? extends EventHandler<R>> listeners, EventPolicy<R> policy) {
            this.event = event;
            this.listeners = listeners;
            this.policy = policy;
        }

        private void submit(Runnable action) {
            if (settled.get()) return;
            FutureTask<Void> task = new FutureTask<>(() -> {
                Thread current = Thread.currentThread();
                runningThread.set(current);
                try {
                    if (!settled.get()) action.run();
                    return null;
                } finally {
                    runningThread.compareAndSet(current, null);
                }
            });
            invocation.set(task);
            if (settled.get()) {
                invocation.compareAndSet(task, null);
                task.cancel(false);
                return;
            }
            try {
                workers.execute(task);
                if (settled.get() && workers.remove(task)) task.cancel(false);
            } catch (RejectedExecutionException overloaded) {
                invocation.compareAndSet(task, null);
                fail(new PluginOverloadedException(overloaded));
            }
        }

        private void invoke(int index) {
            if (settled.get()) return;
            while (index < listeners.size() && !listeners.get(index).active()) index++;
            if (index >= listeners.size()) {
                succeed(policy.defaultResult().get());
                return;
            }
            final int currentIndex = index;
            try {
                CompletionStage<R> stage = Objects.requireNonNull(listeners.get(index).handle(event), "event listener stage");
                CompletableFuture<R> cancellable = stage.toCompletableFuture();
                listenerFuture.set(cancellable);
                if (settled.get()) {
                    if (listenerFuture.compareAndSet(cancellable, null)) cancelListener(cancellable);
                    return;
                }
                // A plugin may complete its stage on any thread. Only enqueue there; process its
                // result and invoke the next plugin listener on an event worker.
                stage.whenComplete((value, failure) -> submit(() -> {
                    listenerFuture.compareAndSet(cancellable, null);
                    if (failure != null) handleFailure(currentIndex, failure);
                    else if (!policy.validResult().test(value)) {
                        handleFailure(currentIndex, new IllegalStateException("Event listener returned an invalid result"));
                    } else if (policy.terminalResult().test(value)) succeed(value);
                    else invoke(currentIndex + 1);
                }));
            } catch (Throwable failure) {
                handleFailure(currentIndex, failure);
            }
        }

        private void handleFailure(int index, Throwable failure) {
            if (settled.get()) return;
            if (!policy.isolateListenerFailures()) {
                fail(failure);
                return;
            }
            try {
                policy.isolatedFailure().accept(event, failure);
            } catch (Throwable ignored) {
                // Best-effort logging cannot break notification delivery.
            }
            submit(() -> invoke(index + 1));
        }

        private void succeed(R value) {
            if (settled.compareAndSet(false, true)) result.complete(value);
        }

        private void fail(Throwable failure) {
            if (settled.compareAndSet(false, true)) result.completeExceptionally(failure);
        }

        private void cleanup() {
            settled.set(true);
            ScheduledFuture<?> timeoutTask = deadline;
            if (timeoutTask != null) timeoutTask.cancel(false);
            FutureTask<Void> task = invocation.getAndSet(null);
            if (task != null && !task.isDone()) {
                workers.remove(task);
                boolean interrupt = (result.isCancelled() || result.isCompletedExceptionally())
                        && runningThread.get() != Thread.currentThread();
                task.cancel(interrupt);
            }
            cancelListener(listenerFuture.getAndSet(null));

            PendingEvent<?> next = null;
            synchronized (AsyncEventDispatcher.this) {
                pending.remove(this);
                if (policy.serializeEvents()) {
                    if (serialActive) {
                        serialEventActive = false;
                        serialActive = false;
                    } else {
                        waiting.remove(this);
                    }
                    while (!closed && !serialEventActive && !waiting.isEmpty()) {
                        PendingEvent<?> candidate = waiting.removeFirst();
                        if (candidate.settled.get()) continue;
                        serialEventActive = true;
                        candidate.serialActive = true;
                        next = candidate;
                        break;
                    }
                }
            }
            if (next != null) {
                PendingEvent<?> ready = next;
                ready.submit(() -> ready.invoke(0));
            }
        }
    }
}

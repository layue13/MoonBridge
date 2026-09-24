package dev.strataproxy.route;

import dev.strataproxy.plugin.route.RouteContext;
import dev.strataproxy.plugin.route.RouteDecision;
import dev.strataproxy.plugin.route.RoutePolicy;
import dev.strataproxy.plugin.route.RouteService;
import dev.strataproxy.plugin.route.RouteStage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Evaluates registered routing policies in stage and priority order.
 *
 * <p>Policy callbacks are dispatched to a bounded worker pool, so they are
 * never invoked on a Netty event loop. Their returned stages are composed
 * asynchronously; a worker is not held while a policy waits for I/O. If all
 * policies pass, the engine returns {@code PASS} and the caller applies its
 * stage-specific fallback.</p>
 */
public final class StageRouteEngine implements AutoCloseable {
    private static final int MAX_WORKERS = 8;
    private static final int QUEUE_CAPACITY = 1024;

    private final long perPolicyTimeoutNanos;
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor timer;
    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentHashMap<RouteStage, CopyOnWriteArrayList<RegisteredPolicy>> policies =
            new ConcurrentHashMap<>();
    private final Set<CompletableFuture<RouteDecision>> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates an engine with bounded worker capacity and the timeout applied to
     * each policy invocation.
     *
     * @param perPolicyTimeout maximum duration for one policy to produce a
     *                         decision; must be positive
     * @throws IllegalArgumentException if the timeout is zero or negative
     */
    public StageRouteEngine(Duration perPolicyTimeout) {
        Objects.requireNonNull(perPolicyTimeout, "perPolicyTimeout");
        if (perPolicyTimeout.isZero() || perPolicyTimeout.isNegative()) {
            throw new IllegalArgumentException("perPolicyTimeout must be positive");
        }
        try {
            this.perPolicyTimeoutNanos = perPolicyTimeout.toNanos();
        } catch (ArithmeticException tooLarge) {
            throw new IllegalArgumentException("perPolicyTimeout is too large", tooLarge);
        }

        int workerCount = Math.max(2, Math.min(MAX_WORKERS, Runtime.getRuntime().availableProcessors()));
        this.workers = new ThreadPoolExecutor(
                workerCount,
                workerCount,
                0L,
                TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(QUEUE_CAPACITY),
                namedDaemonFactory("strataproxy-route-worker"),
                new ThreadPoolExecutor.AbortPolicy());
        this.timer = new ScheduledThreadPoolExecutor(1, namedDaemonFactory("strataproxy-route-timeout"));
        this.timer.setRemoveOnCancelPolicy(true);
        this.timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    /**
     * Returns the registration facade owned by one plugin.
     *
     * @param pluginId stable plugin identifier used for diagnostics
     * @return a route service whose registrations are attributed to the plugin
     */
    public RouteService forPlugin(String pluginId) {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalArgumentException("pluginId must not be blank");
        }
        String owner = pluginId;
        return (stage, priority, policy) -> register(owner, stage, priority, policy);
    }

    /**
     * Evaluates policies registered for the context stage.
     *
     * @param context immutable route input
     * @return asynchronous terminal decision, or {@code PASS} when no policy
     *         makes a decision
     */
    public CompletionStage<RouteDecision> evaluate(RouteContext context) {
        Objects.requireNonNull(context, "context");
        if (closed.get()) {
            return CompletableFuture.completedFuture(RouteDecision.reject("Route engine is shutting down."));
        }

        CompletableFuture<RouteDecision> result = new CompletableFuture<>();
        inFlight.add(result);
        result.whenComplete((ignored, failure) -> inFlight.remove(result));
        if (closed.get()) {
            result.complete(RouteDecision.reject("Route engine is shutting down."));
            return result;
        }

        CopyOnWriteArrayList<RegisteredPolicy> registered = policies.get(context.stage());
        List<RegisteredPolicy> snapshot = registered == null ? new ArrayList<>() : new ArrayList<>(registered);
        snapshot.removeIf(registration -> !registration.active.get());
        snapshot.sort(Comparator.comparingInt((RegisteredPolicy registration) -> registration.priority).reversed()
                .thenComparingLong(registration -> registration.sequence));
        if (snapshot.isEmpty()) {
            result.complete(RouteDecision.pass());
            return result;
        }
        evaluatePolicy(snapshot, 0, context, result);
        return result;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        policies.values().forEach(List::clear);
        RouteDecision shutdown = RouteDecision.reject("Route engine is shutting down.");
        inFlight.forEach(future -> future.complete(shutdown));
        workers.shutdownNow();
        timer.shutdownNow();
    }

    private AutoCloseable register(String pluginId, RouteStage stage, int priority, RoutePolicy policy) {
        if (closed.get()) {
            throw new IllegalStateException("Route engine is closed");
        }
        if (stage == null || policy == null) {
            throw new IllegalArgumentException("stage and policy must not be null");
        }
        RegisteredPolicy registration = new RegisteredPolicy(
                pluginId, stage, priority, sequence.getAndIncrement(), policy);
        policies.computeIfAbsent(stage, ignored -> new CopyOnWriteArrayList<>()).add(registration);
        if (closed.get()) {
            registration.close();
            throw new IllegalStateException("Route engine is closed");
        }
        return registration::close;
    }

    private void evaluatePolicy(List<RegisteredPolicy> snapshot,
                                int index,
                                RouteContext context,
                                CompletableFuture<RouteDecision> result) {
        if (result.isDone()) {
            return;
        }
        if (index >= snapshot.size()) {
            result.complete(RouteDecision.pass());
            return;
        }

        RegisteredPolicy registration = snapshot.get(index);
        if (!registration.active.get()) {
            dispatch(() -> evaluatePolicy(snapshot, index + 1, context, result), result);
            return;
        }
        var timedDecision = new CompletableFuture<RouteDecision>();
        final ScheduledFuture<?> timeoutTask;
        try {
            timeoutTask = timer.schedule(
                    () -> timedDecision.completeExceptionally(new java.util.concurrent.TimeoutException()),
                    perPolicyTimeoutNanos, TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException rejected) {
            result.complete(RouteDecision.reject("Route engine is shutting down."));
            return;
        }
        timedDecision.whenComplete((decision, failure) -> {
            timeoutTask.cancel(false);
            if (result.isDone()) {
                return;
            }
            if (failure != null) {
                result.complete(policyFailure(registration));
            } else if (decision.kind() == RouteDecision.Kind.PASS) {
                dispatch(() -> evaluatePolicy(snapshot, index + 1, context, result), result);
            } else {
                result.complete(decision);
            }
        });
        try {
            workers.execute(() -> invokePolicy(registration, context, timedDecision));
        } catch (RejectedExecutionException rejected) {
            timedDecision.completeExceptionally(rejected);
        }
    }

    private static void invokePolicy(RegisteredPolicy registration,
                                     RouteContext context,
                                     CompletableFuture<RouteDecision> timedDecision) {
        if (timedDecision.isDone()) {
            return;
        }
        try {
            CompletionStage<RouteDecision> policyStage = registration.policy.route(context);
            if (policyStage == null) {
                throw new NullPointerException("Route policy returned null");
            }
            policyStage.whenComplete((decision, failure) -> {
                if (failure != null) {
                    timedDecision.completeExceptionally(failure);
                } else if (decision == null) {
                    timedDecision.completeExceptionally(new NullPointerException("Route policy returned null"));
                } else {
                    timedDecision.complete(decision);
                }
            });
        } catch (Throwable failure) {
            timedDecision.completeExceptionally(failure);
        }
    }

    private void dispatch(Runnable continuation, CompletableFuture<RouteDecision> result) {
        if (result.isDone()) {
            return;
        }
        try {
            workers.execute(continuation);
        } catch (RejectedExecutionException rejected) {
            result.complete(RouteDecision.reject("Route policy capacity is exhausted."));
        }
    }

    private static RouteDecision policyFailure(RegisteredPolicy registration) {
        return RouteDecision.reject("Route policy from plugin '" + registration.pluginId + "' failed or timed out.");
    }

    private static ThreadFactory namedDaemonFactory(String prefix) {
        AtomicLong threadId = new AtomicLong();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + threadId.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private final class RegisteredPolicy {
        private final String pluginId;
        private final RouteStage stage;
        private final int priority;
        private final long sequence;
        private final RoutePolicy policy;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private RegisteredPolicy(String pluginId, RouteStage stage, int priority, long sequence, RoutePolicy policy) {
            this.pluginId = pluginId;
            this.stage = stage;
            this.priority = priority;
            this.sequence = sequence;
            this.policy = policy;
        }

        private void close() {
            if (active.compareAndSet(true, false)) {
                CopyOnWriteArrayList<RegisteredPolicy> stagePolicies = policies.get(stage);
                if (stagePolicies != null) {
                    stagePolicies.remove(this);
                    if (stagePolicies.isEmpty()) {
                        policies.remove(stage, stagePolicies);
                    }
                }
            }
        }
    }
}

package dev.strataproxy.route;

import dev.strataproxy.plugin.route.RouteContext;
import dev.strataproxy.plugin.route.RouteDecision;
import dev.strataproxy.plugin.route.RoutePolicy;
import dev.strataproxy.plugin.route.RouteService;
import dev.strataproxy.plugin.route.RouteStage;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Dispatches each route request to its single owning plugin. */
public final class StageRouteEngine implements AutoCloseable {
    private final AtomicReference<Registration> initial = new AtomicReference<>();
    private final ConcurrentHashMap<String, Registration> transfers = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<RouteDecision>> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor timer;

    public StageRouteEngine() {
        int count = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
        workers = new ThreadPoolExecutor(count, count, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1024), daemonFactory("strataproxy-route-worker"),
                new ThreadPoolExecutor.AbortPolicy());
        timer = new ScheduledThreadPoolExecutor(1, daemonFactory("strataproxy-route-timeout"));
        timer.setRemoveOnCancelPolicy(true);
        timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    public RouteService forPlugin(String pluginId) {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalArgumentException("pluginId must not be blank");
        }
        return new RouteService() {
            @Override
            public AutoCloseable registerInitial(Duration timeout, RoutePolicy policy) {
                return StageRouteEngine.this.registerInitial(pluginId, timeout, policy);
            }

            @Override
            public AutoCloseable registerTransfer(String routeKey, Duration timeout, RoutePolicy policy) {
                return StageRouteEngine.this.registerTransfer(pluginId, routeKey, timeout, policy);
            }
        };
    }

    public CompletionStage<RouteDecision> evaluate(RouteContext context) {
        Objects.requireNonNull(context, "context");
        if (closed.get()) {
            return CompletableFuture.completedFuture(RouteDecision.reject("Route engine is shutting down."));
        }
        Registration registration = context.stage() == RouteStage.INITIAL
                ? initial.get() : transfers.get(key(context.routeKey()));
        if (registration == null || !registration.active.get()) {
            return CompletableFuture.completedFuture(RouteDecision.pass());
        }

        var result = new CompletableFuture<RouteDecision>();
        inFlight.add(result);
        if (closed.get()) {
            result.complete(RouteDecision.reject("Route engine is shutting down."));
            inFlight.remove(result);
            return result;
        }
        try {
            var timeout = timer.schedule(() -> result.complete(failure(registration)),
                    registration.timeoutNanos, TimeUnit.NANOSECONDS);
            result.whenComplete((ignored, error) -> {
                timeout.cancel(false);
                inFlight.remove(result);
            });
            workers.execute(() -> invoke(registration, context, result));
        } catch (RejectedExecutionException rejected) {
            result.complete(RouteDecision.reject("Route policy capacity is exhausted."));
            inFlight.remove(result);
        }
        return result;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        initial.set(null);
        transfers.clear();
        inFlight.forEach(future -> future.complete(RouteDecision.reject("Route engine is shutting down.")));
        workers.shutdownNow();
        timer.shutdownNow();
    }

    private AutoCloseable registerInitial(String owner, Duration timeout, RoutePolicy policy) {
        var registration = new Registration(owner, "", timeout, policy);
        ensureOpen();
        if (!initial.compareAndSet(null, registration)) {
            throw new IllegalStateException("Initial route already has a handler");
        }
        if (closed.get()) {
            registration.close();
            throw new IllegalStateException("Route engine is closed");
        }
        return registration::close;
    }

    private AutoCloseable registerTransfer(String owner, String routeKey, Duration timeout, RoutePolicy policy) {
        var normalized = key(routeKey);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("routeKey must not be blank");
        }
        var registration = new Registration(owner, normalized, timeout, policy);
        ensureOpen();
        if (transfers.putIfAbsent(normalized, registration) != null) {
            throw new IllegalStateException("Transfer route already has a handler: " + routeKey);
        }
        if (closed.get()) {
            registration.close();
            throw new IllegalStateException("Route engine is closed");
        }
        return registration::close;
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Route engine is closed");
        }
    }

    private static void invoke(Registration registration, RouteContext context,
                               CompletableFuture<RouteDecision> result) {
        if (result.isDone()) {
            return;
        }
        try {
            CompletionStage<RouteDecision> stage = Objects.requireNonNull(
                    registration.policy.route(context), "Route policy returned null");
            stage.whenComplete((decision, error) -> {
                if (error != null || decision == null) {
                    result.complete(failure(registration));
                } else {
                    result.complete(decision);
                }
            });
        } catch (Throwable error) {
            result.complete(failure(registration));
        }
    }

    private static RouteDecision failure(Registration registration) {
        return RouteDecision.reject("Route policy from plugin '" + registration.owner + "' failed or timed out.");
    }

    private static String key(String routeKey) {
        return routeKey == null ? "" : routeKey.trim().toLowerCase(Locale.ROOT);
    }

    private static ThreadFactory daemonFactory(String prefix) {
        var sequence = new AtomicLong();
        return runnable -> {
            var thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private final class Registration {
        private final String owner;
        private final String routeKey;
        private final long timeoutNanos;
        private final RoutePolicy policy;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private Registration(String owner, String routeKey, Duration timeout, RoutePolicy policy) {
            this.owner = owner;
            this.routeKey = routeKey;
            this.policy = Objects.requireNonNull(policy, "policy");
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            try {
                timeoutNanos = timeout.toNanos();
            } catch (ArithmeticException tooLarge) {
                throw new IllegalArgumentException("timeout is too large", tooLarge);
            }
        }

        private void close() {
            if (active.compareAndSet(true, false)) {
                if (routeKey.isEmpty()) {
                    initial.compareAndSet(this, null);
                } else {
                    transfers.remove(routeKey, this);
                }
            }
        }
    }
}

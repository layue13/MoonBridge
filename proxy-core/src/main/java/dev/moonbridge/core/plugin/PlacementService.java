package dev.moonbridge.core.plugin;

import dev.moonbridge.api.InitialPlacementHandler;
import dev.moonbridge.api.PlacementDecision;
import dev.moonbridge.api.PlayerView;
import dev.moonbridge.api.Plugin;
import dev.moonbridge.core.backend.BackendCatalog;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Runs the single plugin-provided initial placement handler off the caller's thread, with a deadline. */
final class PlacementService {
    private final HostLifecycle lifecycle;
    private final Object lock;
    private final BackendCatalog catalog;
    private final Duration placementTimeout;
    private final ThreadPoolExecutor callbacks;
    private final ScheduledThreadPoolExecutor timer;
    private final Set<PendingPlacement> pendingPlacements = ConcurrentHashMap.newKeySet();
    private InitialPlacementHandler handler;

    PlacementService(HostLifecycle lifecycle, Object lock, BackendCatalog catalog, Duration placementTimeout,
                     ThreadPoolExecutor callbacks, ScheduledThreadPoolExecutor timer) {
        this.lifecycle = lifecycle;
        this.lock = lock;
        this.catalog = catalog;
        this.placementTimeout = placementTimeout;
        this.callbacks = callbacks;
        this.timer = timer;
    }

    /** Only one plugin may place players; the host calls this under its lock while enabling. */
    void install(InitialPlacementHandler candidate, String ownerId) {
        if (handler != null) {
            throw new IllegalStateException("Only one plugin may provide initial placement; conflict at " + ownerId);
        }
        handler = candidate;
    }

    void closeAll() {
        for (PendingPlacement request : pendingPlacements) {
            request.decided().set(true);
            request.result().completeExceptionally(new IllegalStateException("Plugin host closed"));
        }
    }

    void clear() { handler = null; }

    /** Returns empty when no plugin handles placement; plugin code always runs off the caller's event loop. */
    CompletionStage<Optional<PlacementDecision>> placeInitial(PlayerView player) {
        Objects.requireNonNull(player, "player");
        final InitialPlacementHandler handler;
        final CompletableFuture<Optional<PlacementDecision>> result;
        final AtomicBoolean decided;
        final AtomicReference<Runnable> callback;
        final AtomicReference<CompletableFuture<PlacementDecision>> pluginStage;
        final PendingPlacement request;
        final ScheduledFuture<?> timeoutTask;
        synchronized (lock) {
            if (!lifecycle.enabled()) {
                return CompletableFuture.failedFuture(new IllegalStateException("Plugin host is not enabled"));
            }
            handler = this.handler;
            if (handler == null) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            result = new CompletableFuture<>();
            decided = new AtomicBoolean();
            callback = new AtomicReference<>();
            pluginStage = new AtomicReference<>();
            request = new PendingPlacement(result, decided);
            pendingPlacements.add(request);
            try {
                timeoutTask = timer.schedule(
                        () -> {
                            if (!decided.compareAndSet(false, true)) return;
                            result.completeExceptionally(new PlacementTimeoutException(placementTimeout));
                        }, placementTimeout.toNanos(), TimeUnit.NANOSECONDS);
            } catch (RuntimeException failure) {
                pendingPlacements.remove(request);
                return CompletableFuture.failedFuture(failure);
            }
        }
        result.whenComplete((ignored, failure) -> {
            decided.set(true);
            timeoutTask.cancel(false);
            pendingPlacements.remove(request);
            Runnable queued = callback.get();
            if (queued != null) callbacks.remove(queued);
            CompletableFuture<PlacementDecision> stage = pluginStage.get();
            if (stage != null && !stage.isDone()) stage.cancel(false);
        });
        Runnable invocation = () -> {
            if (decided.get()) {
                return;
            }
            try {
                CompletionStage<PlacementDecision> stage = Objects.requireNonNull(
                        handler.place(player, ServerViews.snapshot(catalog)), "placement handler stage");
                CompletableFuture<PlacementDecision> cancellable = stage.toCompletableFuture();
                pluginStage.set(cancellable);
                if (result.isDone() && !cancellable.isDone()) cancellable.cancel(false);
                stage.whenComplete((decision, failure) -> {
                    if (failure != null) {
                        if (!decided.compareAndSet(false, true)) return;
                        result.completeExceptionally(failure);
                    } else if (decision == null) {
                        if (!decided.compareAndSet(false, true)) return;
                        IllegalStateException invalid = new IllegalStateException("Placement handler returned null");
                        result.completeExceptionally(invalid);
                    } else {
                        if (decided.compareAndSet(false, true)) result.complete(Optional.of(decision));
                    }
                });
            } catch (Throwable failure) {
                if (!decided.compareAndSet(false, true)) return;
                result.completeExceptionally(failure);
            }
        };
        callback.set(invocation);
        try {
            if (!decided.get()) {
                callbacks.execute(invocation);
                if (decided.get()) callbacks.remove(invocation);
            }
        } catch (RejectedExecutionException overloaded) {
            if (decided.compareAndSet(false, true)) {
                result.completeExceptionally(new PluginOverloadedException(overloaded));
            }
        }
        return result;
    }

    private record PendingPlacement(CompletableFuture<Optional<PlacementDecision>> result,
                                    AtomicBoolean decided) { }
}

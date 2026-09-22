package dev.strataproxy.bootstrap;

import dev.strataproxy.domain.server.ServerLoad;
import dev.strataproxy.domain.server.ServerRegistry;
import dev.strataproxy.infrastructure.observability.ProxyMetrics;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class ProxyObservedLoadReporter implements AutoCloseable {
    private final ServerRegistry registry;
    private final ProxyMetrics metrics;
    private final Duration interval;
    private final ScheduledExecutorService executor;
    private Map<String, PreviousServerCounters> previous = Map.of();

    ProxyObservedLoadReporter(ServerRegistry registry, ProxyMetrics metrics, Duration interval) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        this.executor = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual()
                .name("strataproxy-load-reporter-", 0)
                .factory());
    }

    void start() {
        executor.scheduleAtFixedRate(this::flushSafely, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    void flushOnce() {
        var snapshot = metrics.snapshot();
        var next = new HashMap<String, PreviousServerCounters>();
        var seconds = Math.max(0.001d, interval.toNanos() / 1_000_000_000.0d);
        for (var server : registry.snapshot()) {
            var name = server.descriptor().name();
            var traffic = snapshot.serverTraffic().getOrDefault(name, new ProxyMetrics.ServerTraffic(0, 0));
            var current = new PreviousServerCounters(
                    traffic.frontendToBackendBytes(),
                    traffic.backendToFrontendBytes());
            var prior = previous.getOrDefault(name, current);
            var load = server.load();
            registry.updateLoad(name, new ServerLoad(
                    load.players(),
                    load.softCapacity(),
                    load.hardCapacity(),
                    rate(current.frontendToBackendBytes() - prior.frontendToBackendBytes(), seconds),
                    rate(current.backendToFrontendBytes() - prior.backendToFrontendBytes(), seconds),
                    0,
                    snapshot.eventLoopDelayNanos() / 1_000_000.0d));
            next.put(name, current);
        }
        previous = Map.copyOf(next);
    }

    @Override
    /** Provides close. */
    public void close() {
        executor.shutdownNow();
    }

    private void flushSafely() {
        try {
            flushOnce();
        } catch (RuntimeException ignored) {
            // Load reporting must not take down the proxy runtime.
        }
    }

    private static long rate(long delta, double seconds) {
        return Math.max(0L, Math.round(delta / seconds));
    }

    private record PreviousServerCounters(
            long frontendToBackendBytes,
            long backendToFrontendBytes) {
    }
}

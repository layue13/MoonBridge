package dev.strataproxy.network;

import io.netty.channel.EventLoopGroup;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class NetworkRuntimeMonitor implements AutoCloseable {
    private final EventLoopGroup workerGroup;
    private final ProxyMetrics metrics;
    private final Duration interval;
    private final ScheduledExecutorService scheduler;

    NetworkRuntimeMonitor(EventLoopGroup workerGroup, ProxyMetrics metrics, Duration interval) {
        this.workerGroup = Objects.requireNonNull(workerGroup, "workerGroup");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.interval = interval == null ? Duration.ofSeconds(1) : interval;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "strataproxy-runtime-monitor");
            thread.setDaemon(true);
            return thread;
        });
    }

    void start() {
        scheduler.scheduleWithFixedDelay(this::sample, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    void sample() {
        for (var eventExecutor : workerGroup) {
            var submittedAt = System.nanoTime();
            eventExecutor.execute(() -> metrics.eventLoopDelayNanos(System.nanoTime() - submittedAt));
        }
    }

    @Override
    /** Provides close. */
    public void close() {
        scheduler.shutdownNow();
    }
}

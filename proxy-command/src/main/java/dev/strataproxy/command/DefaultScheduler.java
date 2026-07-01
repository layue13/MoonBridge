package dev.strataproxy.command;

import dev.strataproxy.plugin.service.Scheduler;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class DefaultScheduler implements Scheduler, AutoCloseable {
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            task -> {
                var thread = new Thread(task, "strataproxy-plugin-worker");
                thread.setDaemon(true);
                return thread;
            });

    @Override
    public CompletableFuture<Void> runAsync(Runnable task) {
        return CompletableFuture.runAsync(task, executor);
    }

    @Override
    public AutoCloseable scheduleRepeating(Runnable task, Duration initialDelay, Duration interval) {
        var future = executor.scheduleAtFixedRate(
                task,
                Math.max(0L, initialDelay == null ? 0L : initialDelay.toMillis()),
                Math.max(1L, interval == null ? 1L : interval.toMillis()),
                TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}

package dev.strataproxy.plugin.service;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Lightweight task scheduler exposed to plugins.
 */
public interface Scheduler {
    /**
     * Runs a task asynchronously.
     *
     * @param task task body
     * @return future completed when the task finishes
     */
    CompletableFuture<Void> runAsync(Runnable task);

    /**
     * Schedules a task to run repeatedly.
     *
     * @param task task body
     * @param initialDelay delay before first execution
     * @param interval delay between executions
     * @return handle that cancels future executions when closed
     */
    AutoCloseable scheduleRepeating(Runnable task, Duration initialDelay, Duration interval);
}

package dev.strataproxy.plugin.service;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public interface Scheduler {
    CompletableFuture<Void> runAsync(Runnable task);

    AutoCloseable scheduleRepeating(Runnable task, Duration initialDelay, Duration interval);
}

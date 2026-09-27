package dev.moonbridge.luckperms;

import me.lucko.luckperms.common.plugin.scheduler.SchedulerAdapter;
import me.lucko.luckperms.common.plugin.scheduler.SchedulerTask;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;

final class MoonBridgeScheduler implements SchedulerAdapter {
    private final Executor async = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "MoonBridge-LuckPerms-Timer");
        thread.setDaemon(true);
        return thread;
    });

    @Override public Executor async() { return async; }
    @Override public SchedulerTask asyncLater(Runnable task, long delay, TimeUnit unit) {
        var future = timers.schedule(() -> async.execute(task), delay, unit);
        return () -> future.cancel(false);
    }
    @Override public SchedulerTask asyncRepeating(Runnable task, long interval, TimeUnit unit) {
        var running = new java.util.concurrent.atomic.AtomicBoolean();
        var future = timers.scheduleAtFixedRate(() -> {
            if (running.compareAndSet(false, true)) {
                async.execute(() -> {
                    try { task.run(); }
                    finally { running.set(false); }
                });
            }
        }, interval, interval, unit);
        return () -> future.cancel(false);
    }
    @Override public void shutdownScheduler() {
        timers.shutdownNow();
    }
    @Override public void shutdownExecutor() {
        if (async instanceof ExecutorService service) {
            service.shutdown();
            await(service, 2, TimeUnit.SECONDS);
            if (!service.isTerminated()) {
                service.shutdownNow();
                await(service, 500, TimeUnit.MILLISECONDS);
            }
        }
    }

    private static void await(ExecutorService service, long timeout, TimeUnit unit) {
        try {
            service.awaitTermination(timeout, unit);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            service.shutdownNow();
        }
    }
}

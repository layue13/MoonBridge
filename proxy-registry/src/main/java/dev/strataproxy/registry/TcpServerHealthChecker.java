package dev.strataproxy.registry;

import dev.strataproxy.api.server.ServerHealth;
import dev.strataproxy.api.server.ServerHealthStatus;
import dev.strataproxy.api.server.ServerRegistry;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class TcpServerHealthChecker implements AutoCloseable {
    private final ServerRegistry registry;
    private final Duration interval;
    private final Duration timeout;
    private final ScheduledExecutorService scheduler;

    public TcpServerHealthChecker(ServerRegistry registry, Duration interval, Duration timeout) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.interval = interval == null ? Duration.ofSeconds(5) : interval;
        this.timeout = timeout == null ? Duration.ofSeconds(2) : timeout;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "strataproxy-health-check");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        scheduler.execute(this::checkAll);
        scheduler.scheduleWithFixedDelay(this::checkAll, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    void checkAll() {
        for (var server : registry.snapshot()) {
            var descriptor = server.descriptor();
            var started = System.nanoTime();
            try (var socket = new Socket()) {
                socket.connect(descriptor.address(), Math.toIntExact(timeout.toMillis()));
                var latencyMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                registry.updateHealth(descriptor.name(), ServerHealth.up(latencyMillis));
            } catch (IOException | RuntimeException exception) {
                registry.updateHealth(descriptor.name(), new ServerHealth(
                        ServerHealthStatus.DOWN,
                        -1,
                        1.0d,
                        exception.getClass().getSimpleName(),
                        Instant.now()));
            }
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}

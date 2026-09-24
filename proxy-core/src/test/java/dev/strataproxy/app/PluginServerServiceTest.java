package dev.strataproxy.app;

import dev.strataproxy.registry.InMemoryServerRegistry;
import dev.strataproxy.registry.NoopRegistryStore;
import dev.strataproxy.registry.RegistryPersistenceService;
import dev.strataproxy.api.server.ServerLoad;
import dev.strataproxy.plugin.service.Scheduler;
import dev.strataproxy.plugin.service.ServerRegistration;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PluginServerServiceTest {
    @Test
    void preventsOnePluginFromReplacingAnotherPluginsBackend() {
        var registry = new InMemoryServerRegistry();
        var persistence = new RegistryPersistenceService(registry, NoopRegistryStore.INSTANCE);
        var owner = new PluginServerService(registry, persistence, immediateScheduler(), "owner");
        var other = new PluginServerService(registry, persistence, immediateScheduler(), "other");

        var first = owner.register(new ServerRegistration("survival", new InetSocketAddress("127.0.0.1", 25565)))
                .toCompletableFuture().join();
        var replacement = other.register(new ServerRegistration("survival", new InetSocketAddress("127.0.0.1", 25566)))
                .toCompletableFuture().join();

        assertTrue(first.success());
        assertFalse(replacement.success());
        assertEquals("server_owned_by_other_plugin", replacement.outcome());
        assertEquals(25565, registry.get("survival").orElseThrow().descriptor().address().getPort());
    }

    @Test
    void exposesLiveLoadAndHealthInPluginServerView() {
        var registry = new InMemoryServerRegistry();
        var service = new PluginServerService(registry,
                new RegistryPersistenceService(registry, NoopRegistryStore.INSTANCE),
                immediateScheduler(), "owner");
        service.register(new ServerRegistration("island", new InetSocketAddress("127.0.0.1", 25565)))
                .toCompletableFuture().join();
        registry.updateLoad("island", new ServerLoad(7, 0, 0, 1024, 2048, 0, 2.5));

        var view = service.find("island").orElseThrow();
        assertEquals(7, view.load().players());
        assertEquals(1024, view.load().inboundBytesPerSecond());
        assertEquals("UP", view.health().status().name());
        assertTrue(view.availableForNewConnections());
    }

    private static Scheduler immediateScheduler() {
        return new Scheduler() {
            @Override
            public CompletableFuture<Void> runAsync(Runnable task) {
                task.run();
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public AutoCloseable scheduleRepeating(Runnable task, Duration initialDelay, Duration interval) {
                return () -> { };
            }
        };
    }
}

package dev.strataproxy.bootstrap;

import dev.strataproxy.infrastructure.registry.memory.InMemoryServerRegistry;
import dev.strataproxy.infrastructure.registry.persistence.NoopRegistryStore;
import dev.strataproxy.infrastructure.registry.persistence.RegistryPersistenceService;
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

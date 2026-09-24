package dev.strataproxy.app;

import dev.strataproxy.core.backend.InMemoryBackendCatalog;
import dev.strataproxy.core.backend.BackendId;
import dev.strataproxy.core.backend.BackendOwner;
import dev.strataproxy.core.backend.BackendRegistration;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class StaticBackendsTest {
    @Test
    void configuredAndPluginServersShareTheCatalog() {
        var catalog = new InMemoryBackendCatalog();
        var configuration = new ProxyConfiguration("127.0.0.1:25577", ProxyConfiguration.Authentication.OFFLINE, List.of(
                new ProxyConfiguration.Backend("lobby", "127.0.0.1:25565", Map.of("role", "spawn"), 100)));

        StaticBackends.register(configuration, catalog);
        catalog.register(new BackendRegistration(new BackendId("island"),
                new BackendOwner("plugin:islands", 1), URI.create("tcp://127.0.0.1:25566"), 40));

        assertEquals(2, catalog.snapshot().size());
        var view = catalog.find(new BackendId("lobby")).orElseThrow();
        assertEquals("lobby", view.handle().id().value());
        assertEquals("tcp://127.0.0.1:25565", view.address().toString());
        assertEquals(Map.of("role", "spawn"), view.tags());
    }
}

package dev.strataproxy.core.plugin;

import dev.strataproxy.api.InitialPlacementHandler;
import dev.strataproxy.api.PlacementDecision;
import dev.strataproxy.api.PlayerIdentity;
import dev.strataproxy.api.PlayerView;
import dev.strataproxy.api.Players;
import dev.strataproxy.api.Plugin;
import dev.strataproxy.api.PluginContext;
import dev.strataproxy.api.ServerDefinition;
import dev.strataproxy.api.ServerRegistration;
import dev.strataproxy.api.TransferResult;
import dev.strataproxy.api.TransferStatus;
import dev.strataproxy.core.backend.BackendCatalog;
import dev.strataproxy.core.backend.InMemoryBackendCatalog;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginHostTest {
    private static final PlayerView PLAYER = new PlayerView(
            new PlayerIdentity(UUID.randomUUID(), 1), "TestPlayer", Optional.empty());

    @Test
    void pluginRegistrationsAreScopedToTheirOwner() {
        BackendCatalog catalog = new InMemoryBackendCatalog();
        CapturingPlugin first = new CapturingPlugin("first", false);
        CapturingPlugin second = new CapturingPlugin("second", false);
        PluginHost host = new PluginHost(catalog, players(), Duration.ofSeconds(1));

        host.load(List.of(first, second));
        host.enable();

        assertTrue(first.context.servers().find("first").isPresent());
        assertTrue(second.context.servers().find("second").isPresent());
        assertThrows(IllegalStateException.class, () -> first.context.servers().register(server("second")));
        assertTrue(second.context.servers().find("second").isPresent());
        host.close();

        assertTrue(catalog.find(new dev.strataproxy.core.backend.BackendId("first")).isEmpty());
        assertTrue(catalog.find(new dev.strataproxy.core.backend.BackendId("second")).isEmpty());
    }

    @Test
    void replacingRegistrationInvalidatesOldHandle() {
        CapturingPlugin plugin = new CapturingPlugin("unused", false);
        PluginHost host = new PluginHost(new InMemoryBackendCatalog(), players(), Duration.ofSeconds(1));
        host.load(List.of(plugin));
        ServerRegistration old = plugin.context.servers().register(server("dynamic"));
        ServerRegistration current = plugin.context.servers().register(server("dynamic"));

        assertThrows(IllegalStateException.class, () -> old.update(server("dynamic")));
        current.update(new ServerDefinition("dynamic", URI.create("tcp://127.0.0.1:25566"),
                Map.of(), 20, Map.of()));
        host.close();
        assertThrows(IllegalStateException.class, () -> current.unregister());
    }

    @Test
    void placementCallbackTimesOut() {
        CapturingPlugin plugin = new CapturingPlugin("placement", true);
        BackendCatalog catalog = new InMemoryBackendCatalog();
        PluginHost host = new PluginHost(catalog, players(), Duration.ofMillis(40));
        host.load(List.of(plugin));
        host.enable();

        CompletionException failure = assertThrows(CompletionException.class,
                () -> host.placeInitial(PLAYER).toCompletableFuture().join());
        assertInstanceOf(PlacementTimeoutException.class, failure.getCause());
        assertTrue(catalog.find(new dev.strataproxy.core.backend.BackendId("placement")).isEmpty());
        host.close();
    }

    @Test
    void onlyOneInitialPlacementHandlerCanBeInstalled() {
        PluginHost host = new PluginHost(new InMemoryBackendCatalog(), players(), Duration.ofSeconds(1));
        assertThrows(IllegalStateException.class, () -> {
            host.load(List.of(new CapturingPlugin("one", true), new CapturingPlugin("two", true)));
            host.enable();
        });
        try (PluginHost noHandler = new PluginHost(
                new InMemoryBackendCatalog(), players(), Duration.ofSeconds(1))) {
            noHandler.load(List.of(new CapturingPlugin("unused", false)));
            noHandler.enable();
            assertEquals(Optional.empty(), noHandler.placeInitial(PLAYER).toCompletableFuture()
                    .getNow(Optional.empty()));
        }
    }

    private static ServerDefinition server(String name) {
        return new ServerDefinition(name, URI.create("tcp://127.0.0.1:25565"), Map.of(), 20, Map.of());
    }

    private static Players players() {
        return new Players() {
            @Override public Optional<PlayerView> find(PlayerIdentity identity) { return Optional.empty(); }
            @Override public List<PlayerView> online() { return List.of(); }
            @Override public java.util.concurrent.CompletionStage<TransferResult> transfer(
                    PlayerIdentity identity, String backendName) {
                return CompletableFuture.completedFuture(TransferResult.failed("test"));
            }
        };
    }

    private static final class CapturingPlugin implements Plugin {
        private final String backend;
        private final boolean placement;
        private PluginContext context;

        private CapturingPlugin(String backend, boolean placement) {
            this.backend = backend;
            this.placement = placement;
        }

        @Override
        public void onLoad(PluginContext context) {
            this.context = context;
            if (!backend.equals("unused")) {
                context.servers().register(server(backend));
            }
        }

        @Override
        public Optional<InitialPlacementHandler> initialPlacementHandler() {
            return placement ? Optional.of((player, servers) -> new CompletableFuture<>()) : Optional.empty();
        }
    }
}

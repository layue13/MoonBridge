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
import dev.strataproxy.core.backend.BackendHandle;
import dev.strataproxy.core.backend.BackendId;
import dev.strataproxy.core.backend.BackendOwner;
import dev.strataproxy.core.backend.BackendRegistration;
import dev.strataproxy.core.backend.BackendView;
import dev.strataproxy.core.backend.CapacityReservation;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    void concurrentRegistrationCannotSurviveHostClose() throws Exception {
        BlockingCatalog catalog = new BlockingCatalog();
        CapturingPlugin plugin = new CapturingPlugin("unused", false);
        PluginHost host = new PluginHost(catalog, players(), Duration.ofSeconds(1));
        host.load(List.of(plugin));
        host.enable();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            catalog.blockRegistration = true;
            var registration = workers.submit(() -> plugin.context.servers().register(server("late")));
            assertTrue(catalog.registerEntered.await(5, TimeUnit.SECONDS));
            CountDownLatch closeStarted = new CountDownLatch(1);
            var shutdown = workers.submit(() -> {
                closeStarted.countDown();
                host.close();
            });
            assertTrue(closeStarted.await(5, TimeUnit.SECONDS));
            catalog.ownerRemoved.await(1, TimeUnit.SECONDS);
            catalog.continueRegister.countDown();
            registration.get(5, TimeUnit.SECONDS);
            shutdown.get(5, TimeUnit.SECONDS);
            assertTrue(catalog.find(new BackendId("late")).isEmpty());
        } finally {
            catalog.continueRegister.countDown();
            host.close();
            workers.shutdownNow();
        }
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
    void disabledPluginCannotUseRetainedServices() {
        AtomicInteger transfers = new AtomicInteger();
        Players delegate = new Players() {
            @Override public Optional<PlayerView> find(PlayerIdentity identity) { return Optional.of(PLAYER); }
            @Override public List<PlayerView> online() { return List.of(PLAYER); }
            @Override public java.util.concurrent.CompletionStage<TransferResult> transfer(
                    PlayerIdentity identity, String backendName) {
                transfers.incrementAndGet();
                return CompletableFuture.completedFuture(TransferResult.of(TransferStatus.NETWORK_READY));
            }
        };
        CapturingPlugin plugin = new CapturingPlugin("unused", false);
        PluginHost host = new PluginHost(new InMemoryBackendCatalog(), delegate, Duration.ofSeconds(1));
        host.load(List.of(plugin));
        host.enable();
        Players retained = plugin.context.players();
        var retainedServers = plugin.context.servers();
        assertEquals(TransferStatus.NETWORK_READY,
                retained.transfer(PLAYER.identity(), "lobby").toCompletableFuture().join().status());
        host.close();

        assertThrows(IllegalStateException.class, () -> retained.transfer(PLAYER.identity(), "lobby"));
        assertThrows(IllegalStateException.class, retained::online);
        assertThrows(IllegalStateException.class, retainedServers::all);
        assertEquals(1, transfers.get());
    }

    @Test
    void placementFailuresOnlyFailTheirOwnRequest() {
        RecoveringPlacementPlugin plugin = new RecoveringPlacementPlugin();
        BackendCatalog catalog = new InMemoryBackendCatalog();
        PluginHost host = new PluginHost(catalog, players(), Duration.ofMillis(40));
        host.load(List.of(plugin));
        host.enable();

        CompletionException failure = assertThrows(CompletionException.class,
                () -> host.placeInitial(PLAYER).toCompletableFuture().join());
        assertInstanceOf(PlacementTimeoutException.class, failure.getCause());
        assertTrue(catalog.find(new dev.strataproxy.core.backend.BackendId("placement")).isPresent());
        CompletionException transientFailure = assertThrows(CompletionException.class,
                () -> host.placeInitial(PLAYER).toCompletableFuture().join());
        assertEquals("temporary lookup failure", transientFailure.getCause().getMessage());
        assertEquals(Optional.of(PlacementDecision.select("placement")),
                host.placeInitial(PLAYER).toCompletableFuture().join());
        assertEquals(0, plugin.disableCount.get());
        host.close();
    }

    @Test
    void closingHostCompletesPendingPlacementAndIgnoresLatePluginDecision() throws Exception {
        PendingPlacementPlugin plugin = new PendingPlacementPlugin();
        PluginHost host = new PluginHost(new InMemoryBackendCatalog(), players(), Duration.ofSeconds(30));
        host.load(List.of(plugin));
        host.enable();
        CompletableFuture<Optional<PlacementDecision>> placement = host.placeInitial(PLAYER).toCompletableFuture();
        assertTrue(plugin.invoked.await(5, TimeUnit.SECONDS));

        host.close();
        var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> placement.get(1, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        plugin.decision.complete(PlacementDecision.select("late"));
        assertTrue(placement.isCompletedExceptionally());
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

    private static final class RecoveringPlacementPlugin implements Plugin {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger disableCount = new AtomicInteger();

        @Override public void onLoad(PluginContext context) {
            context.servers().register(server("placement"));
        }

        @Override public Optional<InitialPlacementHandler> initialPlacementHandler() {
            return Optional.of((player, servers) -> switch (calls.incrementAndGet()) {
                case 1 -> new CompletableFuture<>();
                case 2 -> CompletableFuture.failedFuture(new IllegalStateException("temporary lookup failure"));
                default -> CompletableFuture.completedFuture(PlacementDecision.select("placement"));
            });
        }

        @Override public void onDisable() { disableCount.incrementAndGet(); }
    }

    private static final class PendingPlacementPlugin implements Plugin {
        private final CountDownLatch invoked = new CountDownLatch(1);
        private final CompletableFuture<PlacementDecision> decision = new CompletableFuture<>();

        @Override public void onLoad(PluginContext context) { }

        @Override public Optional<InitialPlacementHandler> initialPlacementHandler() {
            return Optional.of((player, servers) -> {
                invoked.countDown();
                return decision;
            });
        }
    }

    private static final class BlockingCatalog implements BackendCatalog {
        private final InMemoryBackendCatalog delegate = new InMemoryBackendCatalog();
        private final CountDownLatch registerEntered = new CountDownLatch(1);
        private final CountDownLatch continueRegister = new CountDownLatch(1);
        private final CountDownLatch ownerRemoved = new CountDownLatch(1);
        private volatile boolean blockRegistration;

        @Override public BackendView register(BackendRegistration registration) {
            if (blockRegistration) {
                registerEntered.countDown();
                try {
                    if (!continueRegister.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("registration was not released");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("registration interrupted", interrupted);
                }
            }
            return delegate.register(registration);
        }

        @Override public Optional<BackendView> update(BackendHandle handle, BackendRegistration registration) {
            return delegate.update(handle, registration);
        }
        @Override public boolean remove(BackendHandle handle) { return delegate.remove(handle); }
        @Override public int removeOwner(BackendOwner owner) {
            int count = delegate.removeOwner(owner);
            ownerRemoved.countDown();
            return count;
        }
        @Override public Optional<BackendView> find(BackendId id) { return delegate.find(id); }
        @Override public List<BackendView> snapshot() { return delegate.snapshot(); }
        @Override public Optional<CapacityReservation> reserve(BackendHandle handle, int units) {
            return delegate.reserve(handle, units);
        }
    }
}

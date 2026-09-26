package dev.strataproxy.core.plugin;

import dev.strataproxy.api.InitialPlacementHandler;
import dev.strataproxy.api.AccessDecision;
import dev.strataproxy.api.event.ConnectionAdmissionEvent;
import dev.strataproxy.api.event.Event;
import dev.strataproxy.api.event.EventSubscription;
import dev.strataproxy.api.event.Events;
import dev.strataproxy.api.event.PlayerAdmissionEvent;
import dev.strataproxy.api.event.PlayerDisconnectedEvent;
import dev.strataproxy.api.event.ServerConnectedEvent;

import dev.strataproxy.api.CommandInvocation;
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
import dev.strataproxy.core.backend.InMemoryBackendCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginHostTest {
    private static final PlayerView PLAYER = new PlayerView(
            new PlayerIdentity(UUID.randomUUID(), 1), "TestPlayer", Optional.empty());

    @Test
    void accessChecksRunInLoadOrderOffCallerAndStopAtFirstDenial() throws Exception {
        var calls = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var worker = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var first = new Plugin() {
            @Override public void onLoad(PluginContext context) {
                context.events().subscribe(ConnectionAdmissionEvent.class, event -> {
                    calls.add("first-connection");
                    worker.set(Thread.currentThread());
                    return CompletableFuture.completedFuture(AccessDecision.allow());
                });
                context.events().subscribe(PlayerAdmissionEvent.class, event -> {
                    calls.add("first-login:" + event.authenticated());
                    return CompletableFuture.completedFuture(AccessDecision.allow());
                });
            }
        };
        var second = new Plugin() {
            @Override public void onLoad(PluginContext context) {
                context.events().subscribe(ConnectionAdmissionEvent.class, event -> {
                    calls.add("second-connection");
                    return CompletableFuture.completedFuture(AccessDecision.deny("IP blocked"));
                });
                context.events().subscribe(PlayerAdmissionEvent.class, event -> {
                    calls.add("second-login");
                    return CompletableFuture.completedFuture(AccessDecision.deny("Player blocked"));
                });
            }
        };
        var third = new Plugin() {
            @Override public void onLoad(PluginContext context) {
                context.events().subscribe(ConnectionAdmissionEvent.class, event -> {
                    calls.add("third-connection");
                    return CompletableFuture.completedFuture(AccessDecision.allow());
                });
                context.events().subscribe(PlayerAdmissionEvent.class, event -> {
                    calls.add("third-login");
                    return CompletableFuture.completedFuture(AccessDecision.allow());
                });
            }
        };
        try (var host = new PluginHost(new InMemoryBackendCatalog(), players(), Duration.ofSeconds(1),
                Duration.ofSeconds(1))) {
            host.load(List.of(first, second, third));
            host.enable();
            assertTrue(host.hasSubscribers(ConnectionAdmissionEvent.class));
            assertTrue(host.hasSubscribers(PlayerAdmissionEvent.class));
            AccessDecision connection = host.dispatch(new ConnectionAdmissionEvent(new InetSocketAddress("127.0.0.1", 25565)))
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(AccessDecision.deny("IP blocked"), connection);
            assertNotEquals(Thread.currentThread(), worker.get());
            AccessDecision login = host.dispatch(new PlayerAdmissionEvent(PLAYER,
                            new InetSocketAddress("127.0.0.1", 25565), false))
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(AccessDecision.deny("Player blocked"), login);
            assertEquals(List.of("first-connection", "second-connection", "first-login:false", "second-login"), calls);
        }
    }

    @Test
    void accessChecksFailClosedOnTimeoutAndSkipWorkersWhenNoPluginListens() throws Exception {
        var hostWithoutChecks = new PluginHost(new InMemoryBackendCatalog(), players(), Duration.ofSeconds(1),
                Duration.ofMillis(50));
        try {
            hostWithoutChecks.load(List.of());
            hostWithoutChecks.enable();
            assertFalse(hostWithoutChecks.hasSubscribers(ConnectionAdmissionEvent.class));
            assertFalse(hostWithoutChecks.hasSubscribers(PlayerAdmissionEvent.class));
            assertEquals(AccessDecision.allow(), hostWithoutChecks
                    .dispatch(new ConnectionAdmissionEvent(new InetSocketAddress("127.0.0.1", 25565)))
                    .toCompletableFuture().get(1, TimeUnit.SECONDS));
        } finally {
            hostWithoutChecks.close();
        }

        var pending = new CompletableFuture<AccessDecision>();
        var plugin = new Plugin() {
            @Override public void onLoad(PluginContext context) {
                context.events().subscribe(PlayerAdmissionEvent.class, event -> pending);
            }
        };
        try (var host = new PluginHost(new InMemoryBackendCatalog(), players(), Duration.ofSeconds(1),
                Duration.ofMillis(40))) {
            host.load(List.of(plugin));
            host.enable();
            var result = host.dispatch(new PlayerAdmissionEvent(PLAYER,
                    new InetSocketAddress("127.0.0.1", 25565), true)).toCompletableFuture();
            assertThrows(CompletionException.class, result::join);
            awaitCondition(pending::isCancelled);
        }
    }

    @Test
    void accessCheckExceptionsAndNullDecisionsFailClosed() {
        var plugin = new Plugin() {
            @Override public void onLoad(PluginContext context) {
                context.events().subscribe(ConnectionAdmissionEvent.class, event -> CompletableFuture.failedFuture(
                        new IllegalStateException("ban store unavailable")));
                context.events().subscribe(PlayerAdmissionEvent.class, event -> CompletableFuture.completedFuture(null));
            }
        };
        try (var host = new PluginHost(new InMemoryBackendCatalog(), players(), Duration.ofSeconds(1))) {
            host.load(List.of(plugin));
            host.enable();
            assertThrows(CompletionException.class, () -> host.dispatch(
                    new ConnectionAdmissionEvent(new InetSocketAddress("127.0.0.1", 25565))).toCompletableFuture().join());
            assertThrows(CompletionException.class, () -> host.dispatch(new PlayerAdmissionEvent(PLAYER,
                    new InetSocketAddress("127.0.0.1", 25565), true)).toCompletableFuture().join());
        }
    }

    @Test
    void closingHostCancelsAnOutstandingAccessStage() throws Exception {
        var invoked = new CountDownLatch(1);
        var pending = new CompletableFuture<AccessDecision>();
        var plugin = new Plugin() {
            @Override public void onLoad(PluginContext context) {
                context.events().subscribe(PlayerAdmissionEvent.class, event -> {
                    invoked.countDown();
                    return pending;
                });
            }
        };
        var host = new PluginHost(new InMemoryBackendCatalog(), players(), Duration.ofSeconds(1));
        host.load(List.of(plugin));
        host.enable();
        var result = host.dispatch(new PlayerAdmissionEvent(PLAYER,
                new InetSocketAddress("127.0.0.1", 25565), true)).toCompletableFuture();
        assertTrue(invoked.await(5, TimeUnit.SECONDS));
        host.close();
        awaitCondition(pending::isCancelled);
        assertThrows(CompletionException.class, result::join);
    }

    @Test
    void accessChecksRejectOverloadAndCancelPendingPluginStageOnCallerCancellationOrClose() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var plugin = new Plugin() {
            @Override public void onLoad(PluginContext context) {
                context.events().subscribe(ConnectionAdmissionEvent.class, event -> {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            return CompletableFuture.failedFuture(new IllegalStateException("test timed out"));
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return CompletableFuture.failedFuture(interrupted);
                    }
                    return CompletableFuture.completedFuture(AccessDecision.allow());
                });
            }
        };
        var host = new PluginHost(new InMemoryBackendCatalog(), players(), Duration.ofSeconds(1),
                Duration.ofSeconds(2), 1, 1, 1, 1, 4);
        host.load(List.of(plugin));
        host.enable();
        try {
            var first = host.dispatch(new ConnectionAdmissionEvent(new InetSocketAddress("127.0.0.1", 25565))).toCompletableFuture();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var second = host.dispatch(new ConnectionAdmissionEvent(new InetSocketAddress("127.0.0.2", 25565))).toCompletableFuture();
            var overloaded = host.dispatch(new ConnectionAdmissionEvent(new InetSocketAddress("127.0.0.3", 25565))).toCompletableFuture();
            assertThrows(CompletionException.class, overloaded::join);
            assertTrue(first.cancel(false));
            assertTrue(second.cancel(false), "queued request can be cancelled before plugin invocation");

            var waiting = host.dispatch(new ConnectionAdmissionEvent(new InetSocketAddress("127.0.0.4", 25565))).toCompletableFuture();
            host.close();
            assertThrows(CompletionException.class, waiting::join);
            assertThrows(CompletionException.class, () -> host.dispatch(
                    new ConnectionAdmissionEvent(new InetSocketAddress("127.0.0.5", 25565))).toCompletableFuture().join());
        } finally {
            release.countDown();
            host.close();
        }
    }

    @Test
    void asynchronousAccessStagesAreBoundedAndCancellationReleasesCapacity() throws Exception {
        var invoked = new CountDownLatch(2);
        var stages = Map.of(1, new CompletableFuture<AccessDecision>(),
                2, new CompletableFuture<AccessDecision>(), 3, new CompletableFuture<AccessDecision>());
        var plugin = new Plugin() {
            @Override public void onLoad(PluginContext context) {
                context.events().subscribe(ConnectionAdmissionEvent.class, event -> {
                    invoked.countDown();
                    return stages.get(event.remoteAddress().getPort());
                });
            }
        };
        try (var host = new PluginHost(new InMemoryBackendCatalog(), players(), Duration.ofSeconds(5),
                Duration.ofSeconds(5), 1, 8, 1, 8, 2)) {
            host.load(List.of(plugin));
            host.enable();
            var first = host.dispatch(new ConnectionAdmissionEvent(new InetSocketAddress("127.0.0.1", 1))).toCompletableFuture();
            var second = host.dispatch(new ConnectionAdmissionEvent(new InetSocketAddress("127.0.0.1", 2))).toCompletableFuture();
            assertTrue(invoked.await(5, TimeUnit.SECONDS));
            var overloaded = host.dispatch(new ConnectionAdmissionEvent(new InetSocketAddress("127.0.0.1", 3))).toCompletableFuture();
            assertInstanceOf(PluginOverloadedException.class,
                    assertThrows(CompletionException.class, overloaded::join).getCause());
            first.cancel(false);
            awaitCondition(stages.get(1)::isCancelled);
            var replacement = host.dispatch(new ConnectionAdmissionEvent(new InetSocketAddress("127.0.0.1", 3))).toCompletableFuture();
            stages.get(3).complete(AccessDecision.allow());
            assertEquals(AccessDecision.allow(), replacement.get(5, TimeUnit.SECONDS));
            stages.get(2).complete(AccessDecision.allow());
            assertEquals(AccessDecision.allow(), second.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void subscriptionsCanBeDeclaredInOnEnableAndAreFrozenAfterEnable() {
        var captured = new java.util.concurrent.atomic.AtomicReference<PluginContext>();
        Plugin plugin = new Plugin() {
            @Override public void onLoad(PluginContext context) { captured.set(context); }
            @Override public void onEnable() {
                captured.get().events().subscribe(ConnectionAdmissionEvent.class,
                        event -> CompletableFuture.completedFuture(AccessDecision.allow()));
            }
        };
        var host = new PluginHost(new InMemoryBackendCatalog(), players(), Duration.ofSeconds(1));
        host.load(List.of(plugin));
        host.enable();
        assertTrue(host.hasSubscribers(ConnectionAdmissionEvent.class));
        assertThrows(IllegalStateException.class, () -> captured.get().events().subscribe(
                ConnectionAdmissionEvent.class, event -> CompletableFuture.completedFuture(AccessDecision.allow())));
        host.close();
        assertFalse(host.hasSubscribers(ConnectionAdmissionEvent.class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void unsupportedEventTypesAreRejectedAsSubscriptionTypes() {
        var captured = new java.util.concurrent.atomic.AtomicReference<PluginContext>();
        Plugin plugin = new Plugin() {
            @Override public void onLoad(PluginContext context) { captured.set(context); }
        };
        try (var host = new PluginHost(new InMemoryBackendCatalog(), players(), Duration.ofSeconds(1))) {
            host.load(List.of(plugin));
            Events events = captured.get().events();
            Class<Event<Void>> rootType = (Class) Event.class;
            assertThrows(IllegalArgumentException.class, () -> events.subscribe(
                    rootType, event -> CompletableFuture.completedFuture(null)));
        }
    }

    @Test
    void unsubscribeDuringAccessChainSkipsListenerNotYetInvoked() throws Exception {
        var firstInvoked = new CountDownLatch(1);
        var pending = new CompletableFuture<AccessDecision>();
        var secondInvoked = new AtomicBoolean();
        var secondSubscription = new java.util.concurrent.atomic.AtomicReference<EventSubscription>();
        Plugin plugin = new Plugin() {
            @Override public void onLoad(PluginContext context) {
                context.events().subscribe(PlayerAdmissionEvent.class, event -> {
                    firstInvoked.countDown();
                    return pending;
                });
                secondSubscription.set(context.events().subscribe(PlayerAdmissionEvent.class, event -> {
                    secondInvoked.set(true);
                    return CompletableFuture.completedFuture(AccessDecision.allow());
                }));
            }
        };
        try (var host = new PluginHost(new InMemoryBackendCatalog(), players(), Duration.ofSeconds(1))) {
            host.load(List.of(plugin));
            host.enable();
            var result = host.dispatch(new PlayerAdmissionEvent(PLAYER,
                    new InetSocketAddress("127.0.0.1", 25565), true)).toCompletableFuture();
            assertTrue(firstInvoked.await(5, TimeUnit.SECONDS));
            secondSubscription.get().close();
            pending.complete(AccessDecision.allow());
            assertEquals(AccessDecision.allow(), result.get(5, TimeUnit.SECONDS));
            assertFalse(secondInvoked.get());
        }
    }

    @Test
    void notificationListenersRunOffCallerInOrderAndUnsubscribeSkipsQueuedCallbacks() throws Exception {
        var firstEntered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var connectedDelivered = new CountDownLatch(2);
        var calls = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var callbackThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var secondSubscription = new java.util.concurrent.atomic.AtomicReference<EventSubscription>();
        Plugin plugin = new Plugin() {
            @Override public void onLoad(PluginContext value) {
                value.events().subscribe(ServerConnectedEvent.class, event -> {
                    callbackThread.set(Thread.currentThread());
                    calls.add("connected");
                    connectedDelivered.countDown();
                    firstEntered.countDown();
                    try { releaseFirst.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    return CompletableFuture.completedFuture(null);
                });
                secondSubscription.set(value.events().subscribe(PlayerDisconnectedEvent.class, event -> {
                    calls.add("disconnected");
                    return CompletableFuture.completedFuture(null);
                }));
            }
        };
        var host = new PluginHost(new InMemoryBackendCatalog(), players(), Duration.ofSeconds(1));
        try {
            host.load(List.of(plugin));
            host.enable();
            PlayerView connectedPlayer = new PlayerView(PLAYER.identity(), PLAYER.username(), Optional.of("lobby"));
            host.dispatch(new ServerConnectedEvent(connectedPlayer, Optional.empty()));
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            host.dispatch(new PlayerDisconnectedEvent(PLAYER));
            secondSubscription.get().close();
            secondSubscription.get().close();
            host.dispatch(new ServerConnectedEvent(connectedPlayer, Optional.of("previous")));
            releaseFirst.countDown();
            assertTrue(connectedDelivered.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("connected", "connected"), calls);
            assertNotEquals(Thread.currentThread(), callbackThread.get());
            assertFalse(host.hasSubscribers(PlayerDisconnectedEvent.class));
        } finally {
            releaseFirst.countDown();
            host.close();
        }
    }

    @Test
    void notificationsWaitForAsyncListenerStagesBeforeStartingNextListenerOrEvent() throws Exception {
        var firstEntered = new CountDownLatch(1);
        var firstStage = new CompletableFuture<Void>();
        var calls = new java.util.concurrent.CopyOnWriteArrayList<String>();
        Plugin plugin = new Plugin() {
            @Override public void onLoad(PluginContext context) {
                context.events().subscribe(ServerConnectedEvent.class, event -> {
                    calls.add("first-listener");
                    firstEntered.countDown();
                    return firstStage;
                });
                context.events().subscribe(ServerConnectedEvent.class, event -> {
                    calls.add("second-listener");
                    return CompletableFuture.completedFuture(null);
                });
                context.events().subscribe(PlayerDisconnectedEvent.class, event -> {
                    calls.add("next-event");
                    return CompletableFuture.completedFuture(null);
                });
            }
        };
        PlayerView connectedPlayer = new PlayerView(PLAYER.identity(), PLAYER.username(), Optional.of("lobby"));
        try (var host = new PluginHost(new InMemoryBackendCatalog(), players(), Duration.ofSeconds(1))) {
            host.load(List.of(plugin));
            host.enable();
            var first = host.dispatch(new ServerConnectedEvent(connectedPlayer, Optional.empty())).toCompletableFuture();
            var second = host.dispatch(new PlayerDisconnectedEvent(PLAYER)).toCompletableFuture();
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("first-listener"), calls);
            firstStage.complete(null);
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertEquals(List.of("first-listener", "second-listener", "next-event"), calls);
        }
    }

    @Test
    void notificationTimeoutAdvancesQueueAndNullListenerStageIsIsolated() throws Exception {
        var invocation = new AtomicInteger();
        var timeoutStage = new CompletableFuture<Void>();
        var firstEntered = new CountDownLatch(1);
        var nextListener = new CountDownLatch(1);
        Plugin plugin = new Plugin() {
            @Override public void onLoad(PluginContext context) {
                context.events().subscribe(PlayerDisconnectedEvent.class, event -> {
                    if (invocation.getAndIncrement() == 0) {
                        firstEntered.countDown();
                        return timeoutStage;
                    }
                    return null;
                });
                context.events().subscribe(PlayerDisconnectedEvent.class, event -> {
                    nextListener.countDown();
                    return CompletableFuture.completedFuture(null);
                });
            }
        };
        try (var host = new PluginHost(new InMemoryBackendCatalog(), players(), Duration.ofSeconds(1),
                Duration.ofSeconds(1))) {
            host.load(List.of(plugin));
            host.enable();
            var timedOut = host.dispatch(new PlayerDisconnectedEvent(PLAYER)).toCompletableFuture();
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            Thread.sleep(250); // Leave a clear deadline gap for the queued event to run after promotion.
            var queued = host.dispatch(new PlayerDisconnectedEvent(PLAYER)).toCompletableFuture();
            assertThrows(CompletionException.class, timedOut::join);
            queued.get(5, TimeUnit.SECONDS);
            assertTrue(nextListener.await(5, TimeUnit.SECONDS));
            awaitCondition(timeoutStage::isCancelled);
        }
    }

    @Test
    void notificationQueueIsBoundedAndListenerFailuresDoNotStopOtherListeners() throws Exception {
        var firstEntered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var delivered = new CountDownLatch(129);
        var counter = new AtomicInteger();
        var failOnce = new AtomicBoolean(true);
        Plugin plugin = new Plugin() {
            @Override public void onLoad(PluginContext context) {
                context.events().subscribe(PlayerDisconnectedEvent.class, event -> {
                    if (counter.getAndIncrement() == 0) {
                        firstEntered.countDown();
                        try { releaseFirst.await(5, TimeUnit.SECONDS); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    }
                    delivered.countDown();
                    return CompletableFuture.completedFuture(null);
                });
                context.events().subscribe(PlayerDisconnectedEvent.class,
                        event -> {
                            if (failOnce.compareAndSet(true, false)) {
                                throw new IllegalStateException("isolated listener failure");
                            }
                            return CompletableFuture.completedFuture(null);
                        });
            }
        };
        var host = new PluginHost(new InMemoryBackendCatalog(), players(), Duration.ofSeconds(1));
        try {
            host.load(List.of(plugin));
            host.enable();
            host.dispatch(new PlayerDisconnectedEvent(PLAYER));
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            for (int index = 0; index < 140; index++) host.dispatch(new PlayerDisconnectedEvent(PLAYER));
            releaseFirst.countDown();
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertEquals(129, counter.get(), "one running plus exactly 128 queued notification batches");
        } finally {
            releaseFirst.countDown();
            host.close();
        }
    }

    @Test
    void failedEnableRevokesEarlierPluginsSubscriptions() {
        var subscription = new java.util.concurrent.atomic.AtomicReference<EventSubscription>();
        Plugin first = new Plugin() {
            @Override public void onLoad(PluginContext context) {
                subscription.set(context.events().subscribe(PlayerDisconnectedEvent.class,
                        event -> CompletableFuture.completedFuture(null)));
            }
        };
        Plugin failing = new Plugin() {
            @Override public void onLoad(PluginContext context) { }
            @Override public void onEnable() { throw new IllegalStateException("startup failure"); }
        };
        var host = new PluginHost(new InMemoryBackendCatalog(), players(), Duration.ofSeconds(1));
        host.load(List.of(first, failing));
        assertThrows(IllegalStateException.class, host::enable);
        subscription.get().close();
        subscription.get().close();
        assertFalse(host.hasSubscribers(PlayerDisconnectedEvent.class));
        host.close();
    }

    @Test
    void commandsDispatchOffCallerThreadAndReleaseTheirNames() throws Exception {
        var plugin = new CapturingPlugin("unused", false);
        var host = new PluginHost(new InMemoryBackendCatalog(), players(), Duration.ofSeconds(1));
        try {
            host.load(List.of(plugin));
            var observed = new CompletableFuture<CommandInvocation>();
            var worker = new CompletableFuture<Thread>();
            var reply = new CompletableFuture<String>();
            var registration = plugin.context.commands().register("Ping", invocation -> {
                worker.complete(Thread.currentThread());
                observed.complete(invocation);
                invocation.reply("pong");
            });
            assertThrows(IllegalArgumentException.class,
                    () -> plugin.context.commands().register("PING", invocation -> { }));
            host.enable();
            assertFalse(host.dispatchCommand(PLAYER, "/other", reply::complete));
            assertFalse(host.dispatchCommand(PLAYER, "/other", reply::complete,
                    () -> { throw new AssertionError("unknown command must not consume admission"); }));
            assertTrue(host.dispatchCommand(PLAYER, "/PING blocked", reply::complete, () -> false));
            assertFalse(worker.isDone());
            assertTrue(host.dispatchCommand(PLAYER, "/PING hello world", reply::complete));
            assertNotEquals(Thread.currentThread(), worker.get(5, TimeUnit.SECONDS));
            assertEquals("ping", observed.get(5, TimeUnit.SECONDS).name());
            assertEquals("hello world", observed.get(5, TimeUnit.SECONDS).arguments());
            assertEquals("pong", reply.get(5, TimeUnit.SECONDS));
            registration.unregister();
            registration.unregister();
            assertFalse(host.dispatchCommand(PLAYER, "/ping", text -> { }));
        } finally {
            host.close();
        }
        assertThrows(IllegalStateException.class,
                () -> plugin.context.commands().register("late", invocation -> { }));
    }

    @Test
    void pluginShutdownHasOneBoundedDeadlineEvenIfDisableIgnoresInterrupts() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var context = new java.util.concurrent.atomic.AtomicReference<PluginContext>();
        var plugin = new Plugin() {
            @Override public void onLoad(PluginContext value) { context.set(value); }
            @Override public void onDisable() {
                entered.countDown();
                while (release.getCount() != 0) {
                    try { release.await(100, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException ignored) { /* Simulate a broken plugin. */ }
                }
            }
        };
        var host = new PluginHost(new InMemoryBackendCatalog(), players(), Duration.ofSeconds(1),
                1, 1, Duration.ofMillis(100));
        try {
            host.load(List.of(plugin));
            host.enable();
            long start = System.nanoTime();
            host.close();
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
            assertThrows(IllegalStateException.class,
                    () -> context.get().commands().register("late", invocation -> { }));
        } finally {
            release.countDown();
            host.close();
        }
    }

    @Test
    void saturatedCommandWorkersRejectWithoutRunningPluginCodeOnCaller() throws Exception {
        var plugin = new CapturingPlugin("unused", false);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var host = new PluginHost(new InMemoryBackendCatalog(), players(), Duration.ofSeconds(1), 1, 1);
        try {
            host.load(List.of(plugin));
            plugin.context.commands().register("slow", invocation -> {
                calls.incrementAndGet();
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
            });
            host.enable();
            assertTrue(host.dispatchCommand(PLAYER, "/slow one", text -> { }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(host.dispatchCommand(PLAYER, "/slow two", text -> { }));
            var rejectedReply = new CompletableFuture<String>();
            assertTrue(host.dispatchCommand(PLAYER, "/slow three", rejectedReply::complete));
            assertEquals("Proxy command service is busy. Please try again.",
                    rejectedReply.get(1, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        } finally {
            release.countDown();
            host.close();
        }
    }

    @Test
    void disabledBrokenJarCannotPreventConfiguredPluginFromLoading(@TempDir Path directory) throws Exception {
        serviceJar(directory.resolve("a-selected.jar"), ConfiguredProvider.class.getName());
        serviceJar(directory.resolve("z-disabled-broken.jar"), "missing.DisabledProvider");
        var catalog = new InMemoryBackendCatalog();
        try (var host = new PluginHost(catalog, players(), Duration.ofSeconds(1))) {
            host.loadPlugins(directory, Map.of(ConfiguredProvider.class.getName(), Map.of()));
            host.enable();
            assertTrue(catalog.find(new BackendId("configured")).isPresent());
        }
        assertTrue(catalog.find(new BackendId("configured")).isEmpty());
    }

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
                Map.of(), Map.of()));
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
    void blockingPluginTransferCallbackDoesNotBlockTheSessionThread() throws Exception {
        var underlying = new CompletableFuture<TransferResult>();
        Players delegate = new Players() {
            @Override public Optional<PlayerView> find(PlayerIdentity identity) { return Optional.of(PLAYER); }
            @Override public List<PlayerView> online() { return List.of(PLAYER); }
            @Override public java.util.concurrent.CompletionStage<TransferResult> transfer(
                    PlayerIdentity identity, String backendName) { return underlying; }
        };
        var plugin = new CapturingPlugin("unused", false);
        try (var host = new PluginHost(new InMemoryBackendCatalog(), delegate, Duration.ofSeconds(1))) {
            host.load(List.of(plugin));
            host.enable();
            var callbackThread = new CompletableFuture<Thread>();
            var releaseCallback = new CountDownLatch(1);
            var producerDone = new CompletableFuture<Void>();
            plugin.context.players().transfer(PLAYER.identity(), "lobby").whenComplete((result, failure) -> {
                callbackThread.complete(Thread.currentThread());
                try { releaseCallback.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            Thread producer = new Thread(() -> {
                underlying.complete(TransferResult.of(TransferStatus.NETWORK_READY));
                producerDone.complete(null);
            }, "simulated-session-io");
            try {
                producer.start();
                assertNotEquals(producer, callbackThread.get(5, TimeUnit.SECONDS));
                producerDone.get(1, TimeUnit.SECONDS);
            } finally {
                releaseCallback.countDown();
                producer.join(5_000);
            }
        }
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
        assertTrue(plugin.firstDecision.isCancelled(), "timed-out placement should cancel unfinished plugin work");
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
        assertTrue(plugin.decision.isCancelled(), "host close should cancel the plugin's unfinished stage");
        plugin.decision.complete(PlacementDecision.select("late"));
        assertTrue(placement.isCompletedExceptionally());
    }

    @Test
    void closingHostInterruptsRunningPlacementBeforePluginDisable() throws Exception {
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch callbackInterrupted = new CountDownLatch(1);
        AtomicBoolean interruptedBeforeDisable = new AtomicBoolean();
        Plugin plugin = new Plugin() {
            @Override public void onLoad(PluginContext context) { }

            @Override public Optional<InitialPlacementHandler> initialPlacementHandler() {
                return Optional.of((player, servers) -> {
                    callbackStarted.countDown();
                    try {
                        Thread.sleep(30_000);
                        return CompletableFuture.failedFuture(new IllegalStateException("callback was not interrupted"));
                    } catch (InterruptedException interrupted) {
                        callbackInterrupted.countDown();
                        Thread.currentThread().interrupt();
                        return CompletableFuture.failedFuture(interrupted);
                    }
                });
            }

            @Override public void onDisable() {
                try {
                    interruptedBeforeDisable.set(callbackInterrupted.await(1, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        try (var host = new PluginHost(new InMemoryBackendCatalog(), players(), Duration.ofSeconds(5), 1, 1)) {
            host.load(List.of(plugin));
            host.enable();
            var placement = host.placeInitial(PLAYER).toCompletableFuture();
            assertTrue(callbackStarted.await(5, TimeUnit.SECONDS));
            host.close();
            assertTrue(interruptedBeforeDisable.get(), "plugin callback should be interrupted before onDisable");
            assertTrue(placement.isCompletedExceptionally());
        }
    }

    @Test
    void cancelingQueuedPlacementFreesCallbackQueue() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        Plugin plugin = new Plugin() {
            @Override public void onLoad(PluginContext context) { }

            @Override public Optional<InitialPlacementHandler> initialPlacementHandler() {
                return Optional.of((player, servers) -> {
                    if (invocations.incrementAndGet() == 1) {
                        firstEntered.countDown();
                        try {
                            if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                                return CompletableFuture.failedFuture(new IllegalStateException("first callback was not released"));
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return CompletableFuture.failedFuture(interrupted);
                        }
                    }
                    return CompletableFuture.completedFuture(PlacementDecision.select("lobby"));
                });
            }
        };
        try (var host = new PluginHost(new InMemoryBackendCatalog(), players(),
                Duration.ofSeconds(5), 1, 1)) {
            host.load(List.of(plugin));
            host.enable();
            var first = host.placeInitial(PLAYER).toCompletableFuture();
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            var abandoned = host.placeInitial(PLAYER).toCompletableFuture();
            assertTrue(abandoned.cancel(false));
            var next = host.placeInitial(PLAYER).toCompletableFuture();
            releaseFirst.countDown();
            assertEquals(Optional.of(PlacementDecision.select("lobby")), first.get(5, TimeUnit.SECONDS));
            assertEquals(Optional.of(PlacementDecision.select("lobby")), next.get(5, TimeUnit.SECONDS));
            assertEquals(2, invocations.get());
        } finally {
            releaseFirst.countDown();
        }
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
        return new ServerDefinition(name, URI.create("tcp://127.0.0.1:25565"), Map.of(), Map.of());
    }

    private static void awaitCondition(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "condition did not become true before the deadline");
    }

    private static void serviceJar(Path path, String provider) throws IOException {
        try (var output = new JarOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new JarEntry("META-INF/services/" + Plugin.class.getName()));
            output.write((provider + "\n").getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    public static final class ConfiguredProvider implements Plugin {
        @Override public void onLoad(PluginContext context) {
            context.servers().register(server("configured"));
        }
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
        private final CompletableFuture<PlacementDecision> firstDecision = new CompletableFuture<>();

        @Override public void onLoad(PluginContext context) {
            context.servers().register(server("placement"));
        }

        @Override public Optional<InitialPlacementHandler> initialPlacementHandler() {
            return Optional.of((player, servers) -> switch (calls.incrementAndGet()) {
                case 1 -> firstDecision;
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
    }
}

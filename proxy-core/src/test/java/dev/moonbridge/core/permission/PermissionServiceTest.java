package dev.moonbridge.core.permission;

import dev.moonbridge.api.PlayerIdentity;
import dev.moonbridge.api.PlayerView;
import dev.moonbridge.api.permission.PermissionContext;
import dev.moonbridge.api.permission.PermissionDecision;
import dev.moonbridge.api.permission.PermissionProvider;
import dev.moonbridge.api.permission.PermissionResult;
import dev.moonbridge.api.permission.PermissionSubject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PermissionServiceTest {
    private static final UUID PLAYER_ID = UUID.fromString("eaf467ac-dc62-4f33-81c1-a694cab68135");

    @Test
    void currentServerIsUpdatedAndExplicitTargetServerOverridesIt() throws Exception {
        var observed = new AtomicReference<PermissionContext>();
        var subject = new ProbeSubject() {
            @Override public PermissionDecision check(String node, PermissionContext context) {
                lastNode = node;
                observed.set(context);
                return PermissionDecision.ALLOW;
            }
        };
        try (var service = service(subject)) {
            PlayerIdentity identity = identity(1);
            service.prepare(player(identity, null)).toCompletableFuture().get(3, TimeUnit.SECONDS);

            assertEquals(PermissionResult.ALLOW, service.check(identity, "MoonBridge.Server.Join"));
            assertFalse(observed.get().values().containsKey("backend"),
                    "the proxy must not invent a backend context before initial placement commits");
            assertEquals("moonbridge.server.join", subject.lastNode);

            service.update(player(identity, "lobby"));
            assertEquals(PermissionResult.ALLOW, service.check(identity, "moonbridge.server.join"));
            assertEquals(Set.of("lobby"), observed.get().values().get("backend"));

            service.update(player(identity, "survival"));
            assertEquals(PermissionResult.ALLOW, service.check(identity, "moonbridge.server.join"));
            assertEquals(Set.of("survival"), observed.get().values().get("backend"));

            var target = PermissionContext.empty().with("backend", "minigames")
                    .with("world", "arena");
            assertEquals(PermissionResult.ALLOW, service.check(identity, "moonbridge.server.join", target));
            assertEquals(Set.of("minigames"), observed.get().values().get("backend"),
                    "a proposed destination must replace the live server context");
            assertEquals(Set.of("arena"), observed.get().values().get("world"));
        }
        assertTrue(subject.closed.await(3, TimeUnit.SECONDS));
    }

    @Test
    void releasedConnectionCannotAuthorizeItsReconnectAndLateLoadIsDisposed() throws Exception {
        var oldOpening = new CompletableFuture<PermissionSubject>();
        var oldProviderCalled = new CountDownLatch(1);
        var oldSubject = new ProbeSubject();
        var newSubject = new ProbeSubject();
        PermissionProvider provider = view -> {
            if (view.identity().connectionId() == 1) {
                oldProviderCalled.countDown();
                return oldOpening;
            }
            return CompletableFuture.completedFuture(newSubject);
        };
        try (var service = new PermissionService(Duration.ofSeconds(2))) {
            service.configure(provider);
            PlayerIdentity oldIdentity = identity(1);
            var oldReady = service.prepare(player(oldIdentity, null)).toCompletableFuture();
            assertTrue(oldProviderCalled.await(3, TimeUnit.SECONDS));
            service.release(oldIdentity);
            assertTrue(oldReady.isCompletedExceptionally());

            PlayerIdentity newIdentity = identity(2);
            service.prepare(player(newIdentity, null)).toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(PermissionResult.UNAVAILABLE, service.check(oldIdentity, "test.node"));
            assertEquals(PermissionResult.ALLOW, service.check(newIdentity, "test.node"));

            oldOpening.complete(oldSubject);
            assertTrue(oldSubject.closed.await(3, TimeUnit.SECONDS), "late subject must be closed");
            assertEquals(PermissionResult.ALLOW, service.check(newIdentity, "test.node"),
                    "the stale connection's completion must not replace the reconnect");
        }
        assertTrue(newSubject.closed.await(3, TimeUnit.SECONDS));
    }

    @Test
    void permissionProviderRunsOffCallerEventLoopAndTimeoutFailsClosed() throws Exception {
        var entered = new CountDownLatch(1);
        var finishOpen = new CountDownLatch(1);
        var lateSubject = new ProbeSubject();
        var providerThread = new AtomicReference<String>();
        var preparationRef = new AtomicReference<CompletableFuture<Void>>();
        var threadFailure = new AtomicReference<Throwable>();
        var service = new PermissionService(Duration.ofMillis(150));
        service.configure(view -> {
            providerThread.set(Thread.currentThread().getName());
            entered.countDown();
            try {
                if (!finishOpen.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("test provider timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return CompletableFuture.completedFuture(lateSubject);
        });

        var ioThread = new Thread(() -> {
            try {
                CompletableFuture<Void> preparation = service.prepare(player(identity(9), null)).toCompletableFuture();
                preparationRef.set(preparation);
                // prepare returns while provider.open is deliberately blocked on the bounded loader.
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertFalse(preparation.isDone(), "permission loading must gate login until the provider is ready");
            } catch (Throwable failure) {
                threadFailure.set(failure);
            }
        }, "moonbridge-session-io-permission-test");
        ioThread.start();
        ioThread.join(2000);
        try {
            assertFalse(ioThread.isAlive(), "prepare must not block the caller's event loop");
            if (threadFailure.get() != null) throw new AssertionError("event loop preparation failed", threadFailure.get());
            assertNotEquals(ioThread.getName(), providerThread.get(), "provider.open must run on a loader thread");
            org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> preparationRef.get().get(2, TimeUnit.SECONDS), "load timeout must fail the preparation stage");
            assertEquals(PermissionResult.UNAVAILABLE, service.check(identity(9), "test.node"),
                    "timed out or unready subjects cannot authorize checks");
            finishOpen.countDown();
            assertTrue(lateSubject.closed.await(3, TimeUnit.SECONDS),
                    "a subject arriving after timeout must be disposed");
        } finally {
            finishOpen.countDown();
            service.close();
        }
    }

    @Test
    void closedServiceFailsClosedAndClosesLoadedSubjects() throws Exception {
        var subject = new ProbeSubject();
        var service = service(subject);
        PlayerIdentity identity = identity(4);
        service.prepare(player(identity, null)).toCompletableFuture().get(3, TimeUnit.SECONDS);
        assertEquals(PermissionResult.ALLOW, service.check(identity, "test.node"));
        service.close();
        assertEquals(PermissionResult.UNAVAILABLE, service.check(identity, "test.node"));
        assertTrue(subject.closed.await(3, TimeUnit.SECONDS));
    }

    @Test
    void subjectQueryOrUpdateFailureInvalidatesPermissionState() throws Exception {
        var queryFailureSubject = new ProbeSubject() {
            @Override public PermissionDecision check(String node, PermissionContext context) {
                throw new IllegalStateException("test query failure");
            }
        };
        try (var service = service(queryFailureSubject)) {
            PlayerIdentity identity = identity(5);
            service.prepare(player(identity, null)).toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(PermissionResult.UNAVAILABLE, service.check(identity, "test.node"));
            assertEquals(PermissionResult.UNAVAILABLE, service.check(identity, "test.node"));
        }
        assertTrue(queryFailureSubject.closed.await(3, TimeUnit.SECONDS));

        var updateFailureSubject = new ProbeSubject() {
            @Override public void update(PlayerView player) { throw new IllegalStateException("test update failure"); }
        };
        try (var service = service(updateFailureSubject)) {
            PlayerIdentity identity = identity(6);
            service.prepare(player(identity, null)).toCompletableFuture().get(3, TimeUnit.SECONDS);
            service.update(player(identity, "lobby"));
            assertEquals(PermissionResult.UNAVAILABLE, service.check(identity, "test.node"));
        }
        assertTrue(updateFailureSubject.closed.await(3, TimeUnit.SECONDS));
    }

    private static PermissionService service(PermissionSubject subject) {
        var service = new PermissionService(Duration.ofSeconds(2));
        service.configure(view -> CompletableFuture.completedFuture(subject));
        return service;
    }

    private static PlayerIdentity identity(long connectionId) {
        return new PlayerIdentity(PLAYER_ID, connectionId);
    }

    private static PlayerView player(PlayerIdentity identity, String server) {
        return new PlayerView(identity, "PermissionProbe", server);
    }

    private static class ProbeSubject implements PermissionSubject {
        final CountDownLatch closed = new CountDownLatch(1);
        volatile String lastNode;

        @Override public PermissionDecision check(String node, PermissionContext context) {
            lastNode = node;
            return PermissionDecision.ALLOW;
        }

        @Override public void update(PlayerView player) { }

        @Override public void close() { closed.countDown(); }
    }
}

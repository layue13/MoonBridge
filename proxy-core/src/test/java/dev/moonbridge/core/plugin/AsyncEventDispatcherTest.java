package dev.moonbridge.core.plugin;

import dev.moonbridge.api.AccessDecision;
import dev.moonbridge.api.event.ConnectionAdmissionEvent;
import dev.moonbridge.api.event.Event;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AsyncEventDispatcherTest {
    private static final ConnectionAdmissionEvent EVENT =
            new ConnectionAdmissionEvent(new InetSocketAddress("127.0.0.1", 12345));
    private static final AsyncEventDispatcher.EventPolicy<AccessDecision> POLICY =
            new AsyncEventDispatcher.EventPolicy<>(AccessDecision::allow,
                    decision -> decision != null, decision -> decision instanceof AccessDecision.Denied,
                    false, (event, failure) -> { }, 8, false);

    @Test
    void callerCancellationStopsTheChainAndCancelsPluginStageOffCallerThread() throws Exception {
        var timer = Executors.newSingleThreadScheduledExecutor();
        try (var dispatcher = new AsyncEventDispatcher(Duration.ofSeconds(5), 1, 8, timer,
                Thread.ofPlatform().daemon().name("event-test-worker").factory())) {
            var entered = new CountDownLatch(1);
            var stage = new CompletableFuture<AccessDecision>();
            var cancelledOn = new CompletableFuture<Thread>();
            stage.whenComplete((value, failure) -> cancelledOn.complete(Thread.currentThread()));
            var nextCalled = new AtomicBoolean();
            var result = dispatcher.dispatch(EVENT, List.of(handler(() -> {
                entered.countDown();
                return stage;
            }), handler(() -> {
                nextCalled.set(true);
                return CompletableFuture.completedFuture(AccessDecision.allow());
            })), POLICY).toCompletableFuture();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(result.cancel(false));
            Thread cancellationThread = cancelledOn.get(5, TimeUnit.SECONDS);
            assertNotSame(Thread.currentThread(), cancellationThread);
            assertTrue(stage.isCancelled());
            assertFalse(stage.complete(AccessDecision.allow()));
            assertFalse(nextCalled.get());
        } finally {
            timer.shutdownNow();
        }
    }

    @Test
    void stageReturnedAfterDispatcherCloseIsStillCancelled() throws Exception {
        var timer = Executors.newSingleThreadScheduledExecutor();
        var release = new Semaphore(0);
        try (var dispatcher = new AsyncEventDispatcher(Duration.ofSeconds(5), 1, 8, timer,
                Thread.ofPlatform().daemon().name("event-test-worker").factory())) {
            var entered = new CountDownLatch(1);
            var stage = new CompletableFuture<AccessDecision>();
            var cancelled = new CountDownLatch(1);
            stage.whenComplete((value, failure) -> cancelled.countDown());
            var result = dispatcher.dispatch(EVENT, List.of(handler(() -> {
                entered.countDown();
                // Simulate a plugin that returns its async stage after ignoring interruption.
                release.acquireUninterruptibly();
                return stage;
            })), POLICY).toCompletableFuture();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            dispatcher.close();
            assertTrue(result.isCompletedExceptionally());
            release.release();
            assertTrue(cancelled.await(5, TimeUnit.SECONDS));
            assertTrue(stage.isCancelled());
        } finally {
            release.release();
            timer.shutdownNow();
        }
    }

    private static AsyncEventDispatcher.EventHandler<AccessDecision> handler(
            java.util.function.Supplier<CompletionStage<AccessDecision>> action) {
        return new AsyncEventDispatcher.EventHandler<>() {
            @Override public boolean active() { return true; }
            @Override public CompletionStage<AccessDecision> handle(Event<?> event) { return action.get(); }
        };
    }
}

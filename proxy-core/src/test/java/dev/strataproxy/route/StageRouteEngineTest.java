package dev.strataproxy.route;

import dev.strataproxy.plugin.route.RouteContext;
import dev.strataproxy.plugin.route.RouteDecision;
import dev.strataproxy.plugin.route.RouteStage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StageRouteEngineTest {
    @Test
    void onePluginOwnsInitialRouteUntilItsRegistrationCloses() throws Exception {
        try (var engine = new StageRouteEngine()) {
            var first = engine.forPlugin("spawn").registerInitial(Duration.ofSeconds(1),
                    ignored -> CompletableFuture.completedFuture(RouteDecision.select("spawn-1")));
            assertThrows(IllegalStateException.class, () -> engine.forPlugin("other")
                    .registerInitial(Duration.ofSeconds(1),
                            ignored -> CompletableFuture.completedFuture(RouteDecision.select("other"))));
            assertEquals("spawn-1", engine.evaluate(context(RouteStage.INITIAL, "play.example.net"))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS).serverName());
            first.close();
            assertEquals(RouteDecision.Kind.PASS, engine.evaluate(context(RouteStage.INITIAL, "play.example.net"))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS).kind());
            engine.forPlugin("other").registerInitial(Duration.ofSeconds(1),
                    ignored -> CompletableFuture.completedFuture(RouteDecision.select("other")));
            assertEquals("other", engine.evaluate(context(RouteStage.INITIAL, "play.example.net"))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS).serverName());
        }
    }

    @Test
    void transferRouteDispatchesOnlyToTheOwnerOfItsKey() throws Exception {
        try (var engine = new StageRouteEngine()) {
            engine.forPlugin("islands").registerTransfer("island", Duration.ofSeconds(1),
                    ignored -> CompletableFuture.completedFuture(RouteDecision.select("island-2")));
            engine.forPlugin("instances").registerTransfer("instance", Duration.ofSeconds(1),
                    ignored -> CompletableFuture.completedFuture(RouteDecision.select("instance-3")));
            assertThrows(IllegalStateException.class, () -> engine.forPlugin("other")
                    .registerTransfer("ISLAND", Duration.ofSeconds(1),
                            ignored -> CompletableFuture.completedFuture(RouteDecision.pass())));
            assertEquals("island-2", engine.evaluate(context(RouteStage.TRANSFER, "ISLAND"))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS).serverName());
            assertEquals("instance-3", engine.evaluate(context(RouteStage.TRANSFER, "instance"))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS).serverName());
            assertEquals(RouteDecision.Kind.PASS, engine.evaluate(context(RouteStage.TRANSFER, "unknown"))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS).kind());
        }
    }

    @Test
    void invokesPolicyOffCallingThreadAndWaitsForAsynchronousReadiness() throws Exception {
        try (var engine = new StageRouteEngine()) {
            var calledOn = new AtomicReference<String>();
            var ready = new CompletableFuture<RouteDecision>();
            engine.forPlugin("islands").registerTransfer("island", Duration.ofSeconds(2), context -> {
                calledOn.set(Thread.currentThread().getName());
                return ready;
            });
            var result = engine.evaluate(context(RouteStage.TRANSFER, "island")).toCompletableFuture();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (calledOn.get() == null && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertNotEquals(Thread.currentThread().getName(), calledOn.get());
            assertTrue(calledOn.get().startsWith("strataproxy-route-worker-"));
            assertTrue(!result.isDone());
            ready.complete(RouteDecision.select("island-2"));
            assertEquals("island-2", result.get(2, TimeUnit.SECONDS).serverName());
        }
    }

    @Test
    void rejectsNullResultsExceptionsAndTimeouts() throws Exception {
        try (var engine = new StageRouteEngine()) {
            engine.forPlugin("null-result").registerInitial(Duration.ofSeconds(1), ignored -> null);
            assertEquals(RouteDecision.Kind.REJECT, engine.evaluate(context(RouteStage.INITIAL, ""))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS).kind());
        }
        try (var engine = new StageRouteEngine()) {
            engine.forPlugin("exception").registerInitial(Duration.ofSeconds(1), ignored -> {
                throw new IllegalStateException("sensitive implementation detail");
            });
            var result = engine.evaluate(context(RouteStage.INITIAL, ""))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(RouteDecision.Kind.REJECT, result.kind());
            assertTrue(!result.reason().contains("sensitive implementation detail"));
        }
        try (var engine = new StageRouteEngine()) {
            engine.forPlugin("slow").registerTransfer("island", Duration.ofMillis(40),
                    ignored -> new CompletableFuture<>());
            assertEquals(RouteDecision.Kind.REJECT, engine.evaluate(context(RouteStage.TRANSFER, "island"))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS).kind());
        }
    }

    @Test
    void timesOutEvenWhenCallbackBlocksBeforeReturningItsStage() throws Exception {
        try (var engine = new StageRouteEngine()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            engine.forPlugin("blocking").registerInitial(Duration.ofMillis(100), ignored -> {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return CompletableFuture.completedFuture(RouteDecision.select("too-late"));
            });
            try {
                var result = engine.evaluate(context(RouteStage.INITIAL, "")).toCompletableFuture();
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                assertEquals(RouteDecision.Kind.REJECT, result.get(2, TimeUnit.SECONDS).kind());
            } finally {
                release.countDown();
            }
        }
    }

    private static RouteContext context(RouteStage stage, String key) {
        return new RouteContext(stage, key, 0, null, "", "");
    }
}

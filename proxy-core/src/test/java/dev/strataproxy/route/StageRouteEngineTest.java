package dev.strataproxy.route;

import dev.strataproxy.plugin.route.RouteContext;
import dev.strataproxy.plugin.route.RouteDecision;
import dev.strataproxy.plugin.route.RouteStage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StageRouteEngineTest {
    @Test
    void evaluatesByDescendingPriorityAndKeepsRegistrationOrderForTies() throws Exception {
        try (var engine = new StageRouteEngine(Duration.ofSeconds(1))) {
            var routes = engine.forPlugin("test");
            List<String> calls = new ArrayList<>();
            routes.register(RouteStage.INITIAL, 10, context -> {
                calls.add("first-high");
                return CompletableFuture.completedFuture(RouteDecision.pass());
            });
            routes.register(RouteStage.INITIAL, 10, context -> {
                calls.add("second-high");
                return CompletableFuture.completedFuture(RouteDecision.pass());
            });
            routes.register(RouteStage.INITIAL, 0, context -> {
                calls.add("low");
                return CompletableFuture.completedFuture(RouteDecision.select("spawn-a"));
            });

            RouteDecision decision = engine.evaluate(context(RouteStage.INITIAL))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertEquals(RouteDecision.Kind.SELECT, decision.kind());
            assertEquals("spawn-a", decision.serverName());
            assertEquals(List.of("first-high", "second-high", "low"), calls);
        }
    }

    @Test
    void invokesPolicyOffCallingThreadAndSupportsAsynchronousCompletion() throws Exception {
        try (var engine = new StageRouteEngine(Duration.ofSeconds(1))) {
            var calledOn = new AtomicReference<String>();
            var completion = new CompletableFuture<RouteDecision>();
            engine.forPlugin("test").register(RouteStage.TRANSFER, 0, context -> {
                calledOn.set(Thread.currentThread().getName());
                return completion;
            });

            var result = engine.evaluate(context(RouteStage.TRANSFER)).toCompletableFuture();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (calledOn.get() == null && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertNotEquals(Thread.currentThread().getName(), calledOn.get());
            assertTrue(calledOn.get().startsWith("strataproxy-route-worker-"));

            completion.complete(RouteDecision.select("island-2"));
            assertEquals("island-2", result.get(2, TimeUnit.SECONDS).serverName());
        }
    }

    @Test
    void returnsPassWithoutPoliciesAndUnregistersClosedPolicy() throws Exception {
        try (var engine = new StageRouteEngine(Duration.ofSeconds(1))) {
            assertEquals(RouteDecision.Kind.PASS,
                    engine.evaluate(context(RouteStage.INITIAL)).toCompletableFuture().get(1, TimeUnit.SECONDS).kind());

            var registration = engine.forPlugin("test").register(RouteStage.INITIAL, 0,
                    ignored -> CompletableFuture.completedFuture(RouteDecision.reject("blocked")));
            registration.close();

            assertEquals(RouteDecision.Kind.PASS,
                    engine.evaluate(context(RouteStage.INITIAL)).toCompletableFuture().get(1, TimeUnit.SECONDS).kind());
        }
    }

    @Test
    void rejectsNullResultsAndPolicyFailures() throws Exception {
        try (var engine = new StageRouteEngine(Duration.ofSeconds(1))) {
            engine.forPlugin("null-result").register(RouteStage.INITIAL, 2, ignored -> null);
            RouteDecision result = engine.evaluate(context(RouteStage.INITIAL)).toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);
            assertEquals(RouteDecision.Kind.REJECT, result.kind());
            assertTrue(result.reason().contains("null-result"));
        }

        try (var engine = new StageRouteEngine(Duration.ofSeconds(1))) {
            engine.forPlugin("exception").register(RouteStage.INITIAL, 2, ignored -> {
                throw new IllegalStateException("sensitive implementation detail");
            });
            RouteDecision result = engine.evaluate(context(RouteStage.INITIAL)).toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);
            assertEquals(RouteDecision.Kind.REJECT, result.kind());
            assertTrue(result.reason().contains("exception"));
            assertTrue(!result.reason().contains("sensitive implementation detail"));
        }
    }

    @Test
    void timesOutPolicyAndFailsClosed() throws Exception {
        try (var engine = new StageRouteEngine(Duration.ofMillis(40))) {
            engine.forPlugin("slow").register(RouteStage.TRANSFER, 0,
                    ignored -> new CompletableFuture<>());

            RouteDecision result = engine.evaluate(context(RouteStage.TRANSFER)).toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);

            assertEquals(RouteDecision.Kind.REJECT, result.kind());
            assertTrue(result.reason().contains("slow"));
        }
    }

    @Test
    void timesOutEvenWhenPolicyBlocksBeforeReturningAStage() throws Exception {
        try (var engine = new StageRouteEngine(Duration.ofMillis(100))) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            engine.forPlugin("blocking").register(RouteStage.INITIAL, 0, ignored -> {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return CompletableFuture.completedFuture(RouteDecision.select("too-late"));
            });
            try {
                var result = engine.evaluate(context(RouteStage.INITIAL)).toCompletableFuture();
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                assertEquals(RouteDecision.Kind.REJECT, result.get(2, TimeUnit.SECONDS).kind());
            } finally {
                release.countDown();
            }
        }
    }

    private static RouteContext context(RouteStage stage) {
        return new RouteContext(stage, "", "", 0, "", null, "", "");
    }
}

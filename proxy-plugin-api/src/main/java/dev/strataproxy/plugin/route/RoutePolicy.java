package dev.strataproxy.plugin.route;

import java.util.concurrent.CompletionStage;

/**
 * Asynchronous policy that may participate in routing at a particular stage.
 *
 * <p>Policies may perform asynchronous work such as a database lookup and
 * complete their returned stage with {@link RouteDecision#pass()},
 * {@link RouteDecision#select(String)}, or {@link RouteDecision#reject(String)}.
 * The proxy invokes policies away from Netty event-loop threads. Implementors
 * must not block while deciding a route; the timeout comes from registration.</p>
 *
 * <p>A {@code null} return value or exceptional completion is a policy failure
 * and is handled by the route pipeline as a failure, not as {@code PASS}.</p>
 */
@FunctionalInterface
public interface RoutePolicy {
    /**
     * Asynchronously evaluates this policy for the supplied stage snapshot.
     *
     * @param context immutable routing context
     * @return a stage that completes with a non-null routing decision
     */
    CompletionStage<RouteDecision> route(RouteContext context);
}

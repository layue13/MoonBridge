package dev.strataproxy.plugin.route;

/**
 * Registers plugin route policies for invocation at defined routing stages.
 *
 * <p>For a stage, policies are evaluated from highest priority to lowest
 * priority. Policies with the same priority are evaluated in registration
 * order. A {@link RouteDecision#pass()} continues to the next policy;
 * {@code SELECT} and {@code REJECT} end evaluation for that routing attempt.
 * If all policies pass, the proxy applies its stage-specific fallback.</p>
 *
 * <p>The proxy invokes registered policies away from Netty event-loop threads,
 * supports their asynchronous {@link java.util.concurrent.CompletionStage}
 * results, and applies a proxy-defined timeout. Closing the returned registration
 * removes that policy from future routing attempts.</p>
 */
public interface RouteService {
    /**
     * Registers a policy for one routing stage.
     *
     * @param stage stage at which to invoke the policy
     * @param priority evaluation priority; larger values run first
     * @param policy policy callback
     * @return registration handle whose close method unregisters the policy
     * @throws IllegalArgumentException if {@code stage} or {@code policy} is null
     */
    AutoCloseable register(RouteStage stage, int priority, RoutePolicy policy);
}

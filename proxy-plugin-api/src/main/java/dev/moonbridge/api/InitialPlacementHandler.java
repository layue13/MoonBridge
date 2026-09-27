package dev.moonbridge.api;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * Selects an ordered list of backend candidates for a newly authenticated player. The proxy tries
 * candidates in the returned order until one remains valid and login forwarding starts. It does
 * not change destinations after forwarding starts. Static initial-routing servers are used only
 * when no plugin supplies a handler; a handler's rejection, exception, timeout, or exhausted list
 * never falls back to that static list.
 *
 * <p>Return a stage dedicated to this request; the proxy may cancel it when the player leaves, the
 * request times out, or the host closes. Cancellation is best effort and must not be the plugin's
 * only cleanup mechanism.
 */
@FunctionalInterface
public interface InitialPlacementHandler {
    /**
     * Computes the placement decision. {@code servers} is an immutable snapshot of currently
     * registered backends for discovery. Candidate names are resolved against the live registry
     * when each attempt starts; missing candidates are skipped without waiting. A plugin that
     * waits for a newly created backend must return its name only after that backend registers.
     */
    CompletionStage<PlacementDecision> place(PlayerView player, List<ServerView> servers);
}

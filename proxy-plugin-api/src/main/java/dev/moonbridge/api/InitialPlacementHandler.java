package dev.moonbridge.api;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * Selects the first backend for a newly authenticated player. Return a stage dedicated to this
 * request; the proxy may cancel it when the player leaves, the request times out, or the host
 * closes. Cancellation is best effort and must not be the plugin's only cleanup mechanism.
 */
@FunctionalInterface
public interface InitialPlacementHandler {
    CompletionStage<PlacementDecision> place(PlayerView player, List<ServerView> servers);
}

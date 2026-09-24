package dev.strataproxy.api;

import java.util.List;
import java.util.concurrent.CompletionStage;

/** Selects the first backend for a newly authenticated player. */
@FunctionalInterface
public interface InitialPlacementHandler {
    CompletionStage<PlacementDecision> place(PlayerView player, List<ServerView> servers);
}

package dev.moonbridge.api;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * Produces last-token suggestions asynchronously on bounded plugin workers. The plugin owns prefix matching;
 * the proxy validates and bounds results but does not filter them against the player's current token.
 */
@FunctionalInterface
public interface CommandCompleter {
    CompletionStage<List<String>> complete(CommandCompletion completion) throws Exception;
}

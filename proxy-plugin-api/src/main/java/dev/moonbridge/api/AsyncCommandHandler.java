package dev.moonbridge.api;

import java.util.concurrent.CompletionStage;

/** Handles a proxy command whose work completes asynchronously. */
@FunctionalInterface
public interface AsyncCommandHandler {
    /**
     * Starts command work and returns a stage that completes when the command has finished.
     * The handler itself is invoked on a plugin command worker; asynchronous work should be represented by the stage.
     */
    CompletionStage<Void> execute(CommandInvocation invocation) throws Exception;
}

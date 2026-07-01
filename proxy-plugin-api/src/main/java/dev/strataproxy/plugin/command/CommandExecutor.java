package dev.strataproxy.plugin.command;

import java.util.concurrent.CompletionStage;

/**
 * Handles a command invocation.
 */
@FunctionalInterface
public interface CommandExecutor {
    /**
     * Executes a command.
     *
     * @param context invocation data
     * @return asynchronous command result; implementations may return an already-completed stage
     */
    CompletionStage<CommandResult> execute(CommandContext context);
}

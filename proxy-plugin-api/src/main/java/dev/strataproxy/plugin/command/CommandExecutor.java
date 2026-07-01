package dev.strataproxy.plugin.command;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface CommandExecutor {
    CompletionStage<CommandResult> execute(CommandContext context);
}

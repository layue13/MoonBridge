package dev.strataproxy.plugin.command;

import java.util.Collection;
import java.util.concurrent.CompletionStage;

public interface CommandRegistry {
    void register(CommandSpec command);

    boolean unregister(String name);

    Collection<CommandSpec> commands();

    CompletionStage<CommandResult> execute(CommandSource source, String input);
}

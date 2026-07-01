package dev.strataproxy.plugin.command;

import java.util.Collection;
import java.util.concurrent.CompletionStage;

/**
 * Registry and dispatcher for proxy commands.
 */
public interface CommandRegistry {
    /**
     * Registers a command under its primary name and aliases.
     *
     * @param command command definition
     */
    void register(CommandSpec command);

    /**
     * Removes a command by its primary name or alias.
     *
     * @param name command name or alias
     * @return {@code true} when a command was removed
     */
    boolean unregister(String name);

    /**
     * @return snapshot of registered command definitions
     */
    Collection<CommandSpec> commands();

    /**
     * Parses and dispatches command input.
     *
     * @param source command sender
     * @param input raw input, with or without a leading slash
     * @return asynchronous result describing whether the command was handled and whether it succeeded
     */
    CompletionStage<CommandResult> execute(CommandSource source, String input);
}

package dev.strataproxy.plugin.command;

import java.util.List;

/**
 * Invocation data passed to a command executor.
 *
 * @param source sender that invoked the command
 * @param label command label used by the sender, without a leading slash
 * @param arguments parsed command arguments
 * @param input original command line
 */
public record CommandContext(CommandSource source, String label, List<String> arguments, String input) {
    public CommandContext {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        label = label == null ? "" : label;
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
        input = input == null ? "" : input;
    }
}

package dev.strataproxy.plugin.command;

import java.util.List;

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

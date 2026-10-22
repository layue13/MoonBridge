package dev.moonbridge.api;

import java.util.Objects;

/** Immutable context for completing raw arguments after a known command root, excluding one separator. */
public record CommandCompletion(PlayerView player, String commandName, String arguments) {
    public CommandCompletion {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(commandName, "commandName");
        Objects.requireNonNull(arguments, "arguments");
    }
}

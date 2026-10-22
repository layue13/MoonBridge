package dev.moonbridge.api;

import java.util.Objects;
import java.util.Optional;

/** Permission and source policy for a registered proxy command. */
public record CommandRegistrationOptions(Optional<String> permission, boolean allowConsole) {
    private static final CommandRegistrationOptions DEFAULT = new CommandRegistrationOptions(Optional.empty(), false);

    public CommandRegistrationOptions {
        Objects.requireNonNull(permission, "permission");
        permission.ifPresent(node -> {
            Objects.requireNonNull(node, "permission node");
            if (node.isBlank() || node.length() > 256 || node.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("invalid permission node: " + node);
            }
        });
    }

    public static CommandRegistrationOptions defaults() {
        return DEFAULT;
    }

    public static CommandRegistrationOptions requiring(String permission) {
        return new CommandRegistrationOptions(Optional.of(Objects.requireNonNull(permission, "permission")), false);
    }

    public CommandRegistrationOptions withPermission(String permission) {
        return new CommandRegistrationOptions(Optional.of(Objects.requireNonNull(permission, "permission")), allowConsole);
    }

    public CommandRegistrationOptions withConsole(boolean allowed) {
        return new CommandRegistrationOptions(permission, allowed);
    }
}

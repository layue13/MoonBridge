package dev.strataproxy.plugin.command;

import java.util.List;

public record CommandSpec(
        String name,
        List<String> aliases,
        String permission,
        String description,
        CommandExecutor executor) {
    public CommandSpec {
        name = normalizeName(name);
        var primaryName = name;
        aliases = aliases == null ? List.of() : aliases.stream()
                .map(CommandSpec::normalizeName)
                .filter(alias -> !alias.equals(primaryName))
                .distinct()
                .toList();
        permission = permission == null ? "" : permission.trim();
        description = description == null ? "" : description.trim();
        if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        }
    }

    private static String normalizeName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("command name must not be blank");
        }
        var normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (!normalized.matches("[a-z0-9][a-z0-9_.:-]{0,63}")) {
            throw new IllegalArgumentException("command name contains unsupported characters: " + value);
        }
        return normalized;
    }
}

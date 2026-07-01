package dev.strataproxy.plugin.command;

import java.util.List;

/**
 * Definition for a command exposed through the proxy command registry.
 *
 * @param name primary command name, normalized to lowercase without a leading slash
 * @param aliases alternate command names, normalized and deduplicated
 * @param permission permission required to execute the command, or blank for public commands
 * @param description short human-readable command description
 * @param executor command handler
 */
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

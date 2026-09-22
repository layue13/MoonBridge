package dev.strataproxy.infrastructure.plugin.command;

import dev.strataproxy.plugin.command.CommandContext;
import dev.strataproxy.plugin.command.CommandRegistry;
import dev.strataproxy.plugin.command.CommandResult;
import dev.strataproxy.plugin.command.CommandSource;
import dev.strataproxy.plugin.command.CommandSpec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory command registry and dispatcher.
 */
public final class DefaultCommandRegistry implements CommandRegistry {
    /**
     * Creates DefaultCommandRegistry.
     */
    public DefaultCommandRegistry() {
    }

    private final Map<String, CommandSpec> commands = new ConcurrentHashMap<>();

    @Override
    /** Provides register. */
    public void register(CommandSpec command) {
        commands.put(command.name(), command);
        for (var alias : command.aliases()) {
            commands.put(alias, command);
        }
    }

    @Override
    /** Provides unregister. */
    public boolean unregister(String name) {
        var normalized = normalize(name);
        var command = commands.remove(normalized);
        if (command == null) {
            return false;
        }
        commands.entrySet().removeIf(entry -> entry.getValue() == command);
        return true;
    }

    @Override
    /** Provides commands. */
    public Collection<CommandSpec> commands() {
        return commands.values().stream().distinct().toList();
    }

    @Override
    /** Provides execute. */
    public CompletionStage<CommandResult> execute(CommandSource source, String input) {
        var parsed = ParsedCommand.parse(input);
        if (parsed == null) {
            return CompletableFuture.completedFuture(CommandResult.ignored());
        }
        var command = commands.get(parsed.label());
        if (command == null) {
            return CompletableFuture.completedFuture(CommandResult.ignored());
        }
        if (!command.permission().isBlank() && !source.hasPermission(command.permission())) {
            return CompletableFuture.completedFuture(CommandResult.failure("You do not have permission to run this command."));
        }
        try {
            return command.executor().execute(new CommandContext(source, parsed.label(), parsed.arguments(), parsed.input()))
                    .exceptionally(exception -> CommandResult.failure(rootMessage(exception)));
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(CommandResult.failure(rootMessage(exception)));
        }
    }

    /**
     * Tests whether input resolves to a registered command without executing it.
     *
     * @param input raw command input
     * @return {@code true} when a command name or alias matches
     */
    public boolean canHandle(String input) {
        var parsed = ParsedCommand.parse(input);
        return parsed != null && commands.containsKey(parsed.label());
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        var normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("/") ? normalized.substring(1) : normalized;
    }

    private static String rootMessage(Throwable throwable) {
        var current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        var message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private record ParsedCommand(String label, List<String> arguments, String input) {
        private static ParsedCommand parse(String raw) {
            if (raw == null) {
                return null;
            }
            var input = raw.trim();
            if (input.startsWith("/")) {
                input = input.substring(1).trim();
            }
            if (input.isBlank()) {
                return null;
            }
            var tokens = split(input);
            if (tokens.isEmpty()) {
                return null;
            }
            return new ParsedCommand(tokens.get(0).toLowerCase(Locale.ROOT), List.copyOf(tokens.subList(1, tokens.size())), input);
        }

        private static List<String> split(String value) {
            var result = new ArrayList<String>();
            for (var part : value.split("\\s+")) {
                if (!part.isBlank()) {
                    result.add(part);
                }
            }
            return result;
        }
    }
}

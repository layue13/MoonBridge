package dev.moonbridge.api;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Registers commands handled by this plugin at the proxy. */
public interface Commands {
    /**
     * Reserves a root command name. Names are case insensitive and unique across plugins.
     * An unregistered command continues to the backend unchanged.
     */
    CommandRegistration register(String name, CommandHandler handler, CommandCompleter completer);

    /** Registers a command without argument completion. */
    default CommandRegistration register(String name, CommandHandler handler) {
        return register(name, handler, null);
    }

    /** Registers a command with a permission requirement. Undefined or unavailable permission denies execution. */
    default CommandRegistration register(String name, String permission, CommandHandler handler,
                                         CommandCompleter completer) {
        return register(name, CommandRegistrationOptions.requiring(permission), handler, completer);
    }

    default CommandRegistration register(String name, String permission, CommandHandler handler) {
        return register(name, permission, handler, null);
    }

    /** Registers a command with an explicit permission and console-source policy. */
    default CommandRegistration register(String name, CommandRegistrationOptions options, CommandHandler handler,
                                         CommandCompleter completer) {
        Objects.requireNonNull(options, "options");
        if (!options.equals(CommandRegistrationOptions.defaults())) {
            throw new UnsupportedOperationException("This command platform does not support command options");
        }
        return register(name, handler, completer);
    }

    default CommandRegistration register(String name, CommandRegistrationOptions options, CommandHandler handler) {
        return register(name, options, handler, null);
    }

    /** Registers a command whose handler reports completion asynchronously. */
    default CommandRegistration registerAsync(String name, CommandRegistrationOptions options,
                                              AsyncCommandHandler handler, CommandCompleter completer) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(handler, "handler");
        throw new UnsupportedOperationException("This command platform does not support asynchronous commands");
    }

    /**
     * Executes a known proxy command as the supplied source. Accepts a leading slash, returns false for an unknown
     * root, and completes after the handler returns. Known commands remain consumed when access is denied.
     */
    default CompletionStage<Boolean> execute(CommandSource source, String command) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(command, "command");
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("This command platform does not support command dispatch"));
    }
}

package dev.moonbridge.core.plugin;

import dev.moonbridge.api.AsyncCommandHandler;
import dev.moonbridge.api.CommandCompleter;
import dev.moonbridge.api.CommandCompletion;
import dev.moonbridge.api.CommandInvocation;
import dev.moonbridge.api.CommandRegistration;
import dev.moonbridge.api.CommandRegistrationOptions;
import dev.moonbridge.api.CommandSource;
import dev.moonbridge.api.PlayerIdentity;
import dev.moonbridge.api.PlayerView;
import dev.moonbridge.api.Players;
import dev.moonbridge.api.Plugin;
import dev.moonbridge.api.permission.PermissionResult;
import net.kyori.adventure.text.Component;
import dev.moonbridge.core.permission.PermissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Plugin command registry: dispatch of player and console commands, tab completion, and the bounded worker
 * pool they run on. All plugin code runs off the caller's thread.
 */
final class CommandService {
    private static final Logger LOGGER = LoggerFactory.getLogger(CommandService.class);
    private static final int MAX_PENDING_COMMAND_COMPLETIONS = 128;
    private static final int MAX_PENDING_COMMAND_EXECUTIONS = 128;
    private static final int MAX_COMPLETION_RESULTS = 100;
    // Leave room for bounded protocol encoding overhead beyond suggestion UTF-8 bodies.
    private static final int MAX_COMPLETION_BYTES = 32_000;
    private static final int MAX_COMPLETION_CANDIDATES_SCANNED = 4096;
    private static final Duration COMMAND_COMPLETION_TIMEOUT = Duration.ofSeconds(1);

    private final HostLifecycle lifecycle;
    private final Object lock;
    private final Players players;
    private final PermissionService permissionService;
    private final ThreadPoolExecutor commandWorkers;
    private final ScheduledThreadPoolExecutor timer;
    private final ConcurrentHashMap<String, RegisteredCommand> commands = new ConcurrentHashMap<>();
    private final Set<PendingCompletion> pendingCompletions = ConcurrentHashMap.newKeySet();
    private final Set<CommandExecution> pendingCommandExecutions = ConcurrentHashMap.newKeySet();
    private final AtomicInteger pendingCompletionCount = new AtomicInteger();
    private final AtomicInteger pendingCommandCount = new AtomicInteger();

    CommandService(HostLifecycle lifecycle, Object lock, Players players, PermissionService permissionService,
                   ThreadPoolExecutor commandWorkers, ScheduledThreadPoolExecutor timer) {
        this.lifecycle = lifecycle;
        this.lock = lock;
        this.players = players;
        this.permissionService = permissionService;
        this.commandWorkers = commandWorkers;
        this.timer = timer;
    }

    /** Registers {@code name} for {@code context}; the returned handle unregisters it. */
    CommandRegistration register(PluginContextImpl context, String name, CommandRegistrationOptions options,
                                 AsyncCommandHandler handler, CommandCompleter completer) {
        String normalized = name.toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9_.:-]{1,32}")) {
            throw new IllegalArgumentException("invalid command name: " + name);
        }
        synchronized (context) {
            context.requireActive();
            RegisteredCommand registration = new RegisteredCommand(normalized, context, handler, completer, options);
            if (commands.putIfAbsent(normalized, registration) != null) {
                throw new IllegalArgumentException("Command already registered: /" + normalized);
            }
            return () -> {
                synchronized (lock) {
                    if (commands.remove(normalized, registration)) {
                        for (PendingCompletion pending : registration.pendingCompletions) {
                            finishCompletion(pending, null, new IllegalStateException("Command unregistered"), true);
                        }
                    }
                }
            };
        }
    }

    /** Drops every command a plugin registered and fails its in-flight completions. */
    void removeOwner(PluginContextImpl context) {
        for (RegisteredCommand command : commands.values()) {
            if (command.context == context) {
                for (PendingCompletion pending : command.pendingCompletions) {
                    finishCompletion(pending, null, new IllegalStateException("Plugin unloaded"), true);
                }
            }
        }
        commands.entrySet().removeIf(entry -> entry.getValue().context == context);
    }

    void failPendingExecutions() {
        for (CommandExecution execution : pendingCommandExecutions) {
            execution.finish(new IllegalStateException("Plugin host closed"), null, null);
        }
    }

    void failPendingCompletions() {
        for (PendingCompletion pending : pendingCompletions) {
            finishCompletion(pending, null, new IllegalStateException("Plugin host closed"), true);
        }
    }

    /** Returns up to 100 registered roots matching a case-insensitive prefix without a leading slash. */
    List<String> commandNames(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        if (!lifecycle.enabled()) return List.of();
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return commands.values().stream()
                .filter(command -> command.context.active && command.options.permission().isEmpty()
                        && command.name.startsWith(normalized))
                .map(command -> "/" + command.name)
                .sorted()
                .limit(MAX_COMPLETION_RESULTS)
                .toList();
    }

    /** Returns roots visible to this player; unavailable, undefined, and denied nodes stay hidden. */
    List<String> commandNames(PlayerView player, String prefix) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(prefix, "prefix");
        if (!lifecycle.enabled()) return List.of();
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return commands.values().stream()
                .filter(command -> command.context.active && command.name.startsWith(normalized)
                        && authorized(player, command))
                .map(command -> "/" + command.name)
                .sorted()
                .limit(MAX_COMPLETION_RESULTS)
                .toList();
    }

    /** Filters a root suggestion without hiding suggestions owned only by a backend. */
    boolean commandVisible(PlayerView player, String suggestion) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(suggestion, "suggestion");
        String root = suggestion.startsWith("/") ? suggestion.substring(1) : suggestion;
        int separator = 0;
        while (separator < root.length() && !Character.isWhitespace(root.charAt(separator))) separator++;
        if (separator == 0) return true;
        RegisteredCommand registered = commands.get(root.substring(0, separator).toLowerCase(Locale.ROOT));
        return registered == null || registered.context.active && authorized(player, registered);
    }

    /**
     * Completes arguments for a known slash command. Root-only input belongs to {@link #commandNames(String)};
     * the callback receives arguments after exactly one root separator, preserving all remaining whitespace.
     */
    Optional<CompletionStage<List<String>>> completeCommand(PlayerView player, String input) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(input, "input");
        if (!lifecycle.enabled() || input.length() < 2 || input.charAt(0) != '/') return Optional.empty();
        int end = 1;
        while (end < input.length() && !Character.isWhitespace(input.charAt(end))) end++;
        if (end == input.length()) return Optional.empty();
        String name = input.substring(1, end).toLowerCase(Locale.ROOT);
        RegisteredCommand registered = commands.get(name);
        if (registered == null || !registered.context.active) return Optional.empty();
        if (!authorized(player, registered)) {
            return Optional.of(CompletableFuture.completedFuture(List.of()));
        }
        String arguments = input.substring(end + 1);
        if (registered.completer == null) {
            return Optional.of(CompletableFuture.completedFuture(List.of()));
        }
        synchronized (lock) {
            if (!lifecycle.enabled() || commands.get(name) != registered || !registered.context.active) {
                return Optional.empty();
            }
            if (!reserveCompletionSlot()) {
                return Optional.of(CompletableFuture.failedFuture(new PluginOverloadedException(
                        new RejectedExecutionException("Too many pending command completions"))));
            }
            PendingCompletion pending = new PendingCompletion(registered, player, name, arguments);
            pendingCompletions.add(pending);
            registered.pendingCompletions.add(pending);
            pending.result.whenComplete((ignored, failure) -> {
                if (pending.result.isCancelled()) finishCompletion(pending, null,
                        new java.util.concurrent.CancellationException("Command completion cancelled"), true);
            });
            try {
                pending.timeout = timer.schedule(() -> finishCompletion(pending, null,
                                new TimeoutException("Plugin command completion timed out"), true),
                        COMMAND_COMPLETION_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
                FutureTask<Void> invocation = new FutureTask<>(() -> {
                    invokeCompleter(pending);
                    return null;
                });
                pending.invocation = invocation;
                commandWorkers.execute(invocation);
                if (pending.finished.get()) {
                    invocation.cancel(true);
                    commandWorkers.remove(invocation);
                }
            } catch (RejectedExecutionException overloaded) {
                finishCompletion(pending, null, new PluginOverloadedException(overloaded), true);
            } catch (RuntimeException failure) {
                finishCompletion(pending, null, failure, true);
            }
            return Optional.of(pending.result);
        }
    }

    private boolean reserveCompletionSlot() {
        while (true) {
            int current = pendingCompletionCount.get();
            if (current >= MAX_PENDING_COMMAND_COMPLETIONS) return false;
            if (pendingCompletionCount.compareAndSet(current, current + 1)) return true;
        }
    }

    private void invokeCompleter(PendingCompletion pending) {
        if (pending.finished.get()) return;
        if (!lifecycle.enabled() || !pending.registered.context.active
                || commands.get(pending.commandName) != pending.registered) {
            finishCompletion(pending, null, new IllegalStateException("Command is no longer registered"), true);
            return;
        }
        if (!authorized(pending.player, pending.registered)) {
            finishCompletion(pending, List.of(), null, true);
            return;
        }
        try {
            CompletionStage<List<String>> stage = Objects.requireNonNull(
                    pending.registered.completer.complete(new CommandCompletion(
                            pending.player, pending.commandName, pending.arguments)),
                    "Command completer returned a null stage");
            CompletableFuture<List<String>> cancellable = stage.toCompletableFuture();
            pending.pluginStage.set(cancellable);
            if (pending.finished.get()) cancellable.cancel(true);
            stage.whenCompleteAsync((suggestions, failure) -> {
                if (failure != null) finishCompletion(pending, null, failure, false);
                else {
                    try {
                        finishCompletion(pending, sanitizeSuggestions(
                                Objects.requireNonNull(suggestions, "Command completer returned null suggestions")),
                                null, false);
                    } catch (Throwable invalid) {
                        finishCompletion(pending, null, invalid, false);
                    }
                }
            }, commandWorkers);
        } catch (Throwable failure) {
            finishCompletion(pending, null, failure, true);
        }
    }

    private static List<String> sanitizeSuggestions(List<String> suggestions) {
        var result = new ArrayList<String>(Math.min(suggestions.size(), MAX_COMPLETION_RESULTS));
        var unique = new HashSet<String>();
        int utf8Bytes = 0;
        int scanned = 0;
        for (String suggestion : suggestions) {
            if (scanned++ >= MAX_COMPLETION_CANDIDATES_SCANNED || result.size() >= MAX_COMPLETION_RESULTS) break;
            if (suggestion == null || suggestion.isEmpty() || suggestion.length() > 100 || containsWhitespace(suggestion)
                    || !unique.add(suggestion)) continue;
            int bytes = suggestion.getBytes(StandardCharsets.UTF_8).length;
            if (utf8Bytes + bytes > MAX_COMPLETION_BYTES) continue;
            result.add(suggestion);
            utf8Bytes += bytes;
        }
        return List.copyOf(result);
    }

    private static boolean containsWhitespace(String value) {
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) return true;
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    private void finishCompletion(PendingCompletion pending, List<String> suggestions, Throwable failure,
                                  boolean cancelExecution) {
        if (!pending.finished.compareAndSet(false, true)) return;
        pendingCompletions.remove(pending);
        pending.registered.pendingCompletions.remove(pending);
        pendingCompletionCount.decrementAndGet();
        if (pending.timeout != null) pending.timeout.cancel(false);
        FutureTask<Void> invocation = pending.invocation;
        if (invocation != null && cancelExecution && !invocation.isDone()) {
            commandWorkers.remove(invocation);
            invocation.cancel(true);
        }
        CompletableFuture<List<String>> pluginStage = pending.pluginStage.get();
        if (pluginStage != null && cancelExecution && !pluginStage.isDone()) {
            PluginThreads.PLAYER_COMPLETIONS.execute(() -> pluginStage.cancel(true));
        }
        if (failure != null) pending.result.completeExceptionally(failure);
        else {
            List<String> safeSuggestions = Objects.requireNonNull(suggestions, "suggestions");
            if (!authorized(pending.player, pending.registered)) safeSuggestions = List.of();
            pending.result.complete(safeSuggestions);
        }
    }

    /** Claims only known root commands; caller retains and forwards every other chat frame. */
    boolean dispatchCommand(PlayerView player, String message, Consumer<Component> reply) {
        return dispatchCommand(player, message, reply, () -> true);
    }

    /** Admission is checked only after the command name is known, preserving unknown-command passthrough. */
    boolean dispatchCommand(PlayerView player, String message, Consumer<Component> reply,
                                   BooleanSupplier admission) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(reply, "reply");
        Objects.requireNonNull(admission, "admission");
        if (!lifecycle.enabled() || !message.startsWith("/") || message.length() < 2) return false;
        int end = 1;
        while (end < message.length() && !Character.isWhitespace(message.charAt(end))) end++;
        String name = message.substring(1, end).toLowerCase(Locale.ROOT);
        RegisteredCommand registered = commands.get(name);
        if (registered == null || !registered.context.active) return false;
        if (!admission.getAsBoolean()) return true;
        String arguments = end == message.length() ? "" : message.substring(end + 1);
        CommandInvocation invocation = new CommandInvocation(player, name, arguments,
                component -> { if (registered.context.active) reply.accept(component); });
        CommandExecution execution = beginCommandExecution(registered, null);
        if (execution == null) {
            reply.accept(Component.text("Proxy command service is busy. Please try again."));
            return true;
        }
        try {
            commandWorkers.execute(() -> {
                if (execution.finished.get()) return;
                if (!lifecycle.enabled() || !registered.context.active || commands.get(name) != registered) {
                    execution.finish(null, null, null);
                    return;
                }
                try {
                    if (!authorized(player, registered)) {
                        invocation.reply("You do not have permission to use this command.");
                        execution.finish(null, null, null);
                        return;
                    }
                    invokeAsyncCommand(registered, invocation, execution);
                } catch (Throwable failure) {
                    execution.finish(failure, invocation, "Proxy command failed.");
                }
            });
        } catch (RejectedExecutionException overloaded) {
            execution.finish(overloaded, invocation, "Proxy command service is busy. Please try again.");
        }
        return true;
    }

    /** Dispatches a command from the trusted local console, with no synthetic player identity. */
    boolean dispatchConsoleCommand(String input, Consumer<Component> reply) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(reply, "reply");
        if (!lifecycle.enabled()) return false;
        String message = input.startsWith("/") ? input.substring(1) : input;
        if (message.isEmpty()) return false;
        int end = 0;
        while (end < message.length() && !Character.isWhitespace(message.charAt(end))) end++;
        if (end == 0) return false;
        String name = message.substring(0, end).toLowerCase(Locale.ROOT);
        RegisteredCommand registered = commands.get(name);
        if (registered == null || !registered.context.active) return false;
        if (!registered.options.allowConsole()) {
            reply.accept(Component.text("This command can only be used by a player."));
            return true;
        }
        String arguments = end == message.length() ? "" : message.substring(end + 1);
        CommandSource source = CommandSource.console(component -> {
            if (registered.context.active) reply.accept(component);
        });
        CommandInvocation invocation = new CommandInvocation(source, name, arguments);
        CommandExecution execution = beginCommandExecution(registered, null);
        if (execution == null) {
            reply.accept(Component.text("Proxy command service is busy. Please try again."));
            return true;
        }
        try {
            commandWorkers.execute(() -> {
                if (execution.finished.get()) return;
                if (!lifecycle.enabled() || !registered.context.active || commands.get(name) != registered) {
                    execution.finish(null, null, null);
                    return;
                }
                try {
                    invokeAsyncCommand(registered, invocation, execution);
                } catch (Throwable failure) {
                    execution.finish(failure, invocation, "Proxy command failed.");
                }
            });
        } catch (RejectedExecutionException overloaded) {
            execution.finish(overloaded, invocation, "Proxy command service is busy. Please try again.");
        }
        return true;
    }

    private boolean authorized(PlayerView player, RegisteredCommand command) {
        return command.options.permission().map(node ->
                permissionService.check(player.identity(), node) == PermissionResult.ALLOW).orElse(true);
    }

    CompletionStage<Boolean> executeCommand(PluginContextImpl caller, CommandSource source, String input) {
        synchronized (lock) { return executeCommandLocked(caller, source, input); }
    }

    private CompletionStage<Boolean> executeCommandLocked(PluginContextImpl caller, CommandSource source,
                                                          String input) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(input, "command");
        synchronized (caller) { caller.requireActive(); }
        if (!lifecycle.enabled()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Plugin host is not enabled"));
        }
        String message = input.startsWith("/") ? input.substring(1) : input;
        if (message.isEmpty()) return CompletableFuture.completedFuture(false);
        int end = 0;
        while (end < message.length() && !Character.isWhitespace(message.charAt(end))) end++;
        if (end == 0) return CompletableFuture.completedFuture(false);
        String name = message.substring(0, end).toLowerCase(Locale.ROOT);
        RegisteredCommand registered = commands.get(name);
        if (registered == null || !registered.context.active) return CompletableFuture.completedFuture(false);
        final Optional<PlayerView> requestedPlayer;
        try {
            requestedPlayer = Objects.requireNonNull(source.player(), "command source player");
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (requestedPlayer.isEmpty() && !registered.options.allowConsole()) {
            try {
                source.reply(Component.text("This command can only be used by a player."));
                return CompletableFuture.completedFuture(true);
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
        String arguments = end == message.length() ? "" : message.substring(end + 1);
        CommandInvocation failureInvocation = new CommandInvocation(source, name, arguments);
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        CommandExecution execution = beginCommandExecution(registered, result);
        if (execution == null) {
            result.completeExceptionally(new PluginOverloadedException(
                    new RejectedExecutionException("Too many pending command executions")));
            return result.minimalCompletionStage();
        }
        try {
            commandWorkers.execute(() -> {
                if (execution.finished.get()) return;
                try {
                    if (!lifecycle.enabled() || !registered.context.active || commands.get(name) != registered) {
                        execution.finish(null, null, null);
                        return;
                    }
                    CommandSource effectiveSource = CommandSource.console(component -> {
                        if (registered.context.active) source.reply(component);
                    });
                    if (requestedPlayer.isPresent()) {
                        PlayerIdentity identity = requestedPlayer.get().identity();
                        Optional<PlayerView> current = players.find(identity);
                        if (current.isEmpty()) {
                            source.reply(Component.text("Your player session is no longer active."));
                            execution.finish(null, null, null);
                            return;
                        }
                        PlayerView livePlayer = current.get();
                        effectiveSource = CommandSource.player(livePlayer, component -> {
                            if (registered.context.active) source.reply(component);
                        });
                        if (!authorized(livePlayer, registered)) {
                            effectiveSource.reply(Component.text("You do not have permission to use this command."));
                            execution.finish(null, null, null);
                            return;
                        }
                    }
                    invokeAsyncCommand(registered, new CommandInvocation(effectiveSource, name, arguments), execution);
                } catch (Throwable failure) {
                    execution.finish(failure, failureInvocation, "Proxy command failed.");
                }
            });
        } catch (RejectedExecutionException overloaded) {
            execution.finish(new PluginOverloadedException(overloaded), null,
                    "Proxy command service is busy. Please try again.");
        }
        return result.minimalCompletionStage();
    }

    private CommandExecution beginCommandExecution(RegisteredCommand command, CompletableFuture<Boolean> result) {
        synchronized (lock) { return beginCommandExecutionLocked(command, result); }
    }

    private CommandExecution beginCommandExecutionLocked(RegisteredCommand command,
                                                         CompletableFuture<Boolean> result) {
        if (!lifecycle.enabled()) return null;
        while (true) {
            int current = pendingCommandCount.get();
            if (current >= MAX_PENDING_COMMAND_EXECUTIONS) return null;
            if (pendingCommandCount.compareAndSet(current, current + 1)) break;
        }
        CommandExecution execution = new CommandExecution(command, result);
        pendingCommandExecutions.add(execution);
        return execution;
    }

    private void invokeAsyncCommand(RegisteredCommand command, CommandInvocation invocation,
                                    CommandExecution execution) throws Exception {
        CompletionStage<Void> stage = Objects.requireNonNull(command.handler.execute(invocation),
                "command handler returned a null stage");
        stage.whenComplete((ignored, failure) -> execution.finish(failure, invocation,
                failure == null ? null : "Proxy command failed."));
    }

    private final class RegisteredCommand {
        private final String name;
        private final PluginContextImpl context;
        private final AsyncCommandHandler handler;
        private final CommandCompleter completer;
        private final CommandRegistrationOptions options;
        private final Set<PendingCompletion> pendingCompletions = ConcurrentHashMap.newKeySet();

        private RegisteredCommand(String name, PluginContextImpl context, AsyncCommandHandler handler,
                                  CommandCompleter completer, CommandRegistrationOptions options) {
            this.name = name;
            this.context = context;
            this.handler = handler;
            this.completer = completer;
            this.options = options;
        }
    }

    private final class CommandExecution {
        private final RegisteredCommand command;
        private final CompletableFuture<Boolean> result;
        private final AtomicBoolean finished = new AtomicBoolean();

        private CommandExecution(RegisteredCommand command, CompletableFuture<Boolean> result) {
            this.command = command;
            this.result = result;
        }

        private void finish(Throwable failure, CommandInvocation invocation, String failureMessage) {
            if (!finished.compareAndSet(false, true)) return;
            pendingCommandExecutions.remove(this);
            pendingCommandCount.decrementAndGet();
            if (failure == null) {
                if (result != null) result.complete(true);
                return;
            }
            if (result != null) result.completeExceptionally(failure);
            try {
                LOGGER.warn("Plugin {} command /{} failed", command.context.owner.id(), command.name, failure);
            } catch (Throwable ignored) { }
            if (invocation != null && failureMessage != null && command.context.active) {
                try {
                    invocation.reply(failureMessage);
                } catch (Throwable replyFailure) {
                    if (replyFailure != failure) {
                        try { failure.addSuppressed(replyFailure); }
                        catch (Throwable ignored) { }
                    }
                }
            }
        }
    }

    private final class PendingCompletion {
        private final RegisteredCommand registered;
        private final PlayerView player;
        private final String commandName;
        private final String arguments;
        private final CompletableFuture<List<String>> result = new CompletableFuture<>();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicReference<CompletableFuture<List<String>>> pluginStage = new AtomicReference<>();
        private volatile FutureTask<Void> invocation;
        private volatile ScheduledFuture<?> timeout;

        private PendingCompletion(RegisteredCommand registered, PlayerView player, String commandName, String arguments) {
            this.registered = registered;
            this.player = player;
            this.commandName = commandName;
            this.arguments = arguments;
        }
    }
}

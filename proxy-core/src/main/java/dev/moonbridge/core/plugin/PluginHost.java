package dev.moonbridge.core.plugin;

import dev.moonbridge.api.AccessDecision;
import dev.moonbridge.api.MessageResult;
import dev.moonbridge.api.DisconnectResult;
import dev.moonbridge.api.event.ConnectionAdmissionEvent;
import dev.moonbridge.api.event.Event;
import dev.moonbridge.api.event.EventListener;
import dev.moonbridge.api.event.EventSubscription;
import dev.moonbridge.api.event.Events;
import dev.moonbridge.api.event.PlayerAdmissionEvent;
import dev.moonbridge.api.event.PlayerDisconnectedEvent;
import dev.moonbridge.api.event.ServerConnectedEvent;
import dev.moonbridge.messaging.Endpoint;
import dev.moonbridge.messaging.Message;
import dev.moonbridge.messaging.MessageKind;
import dev.moonbridge.messaging.Messaging;
import dev.moonbridge.messaging.MessagingException;
import dev.moonbridge.messaging.PublishResult;
import dev.moonbridge.messaging.SendResult;
import dev.moonbridge.messaging.internal.LocalMessaging;
import dev.moonbridge.api.InitialPlacementHandler;
import dev.moonbridge.api.AsyncCommandHandler;
import dev.moonbridge.api.CommandHandler;
import dev.moonbridge.api.CommandCompleter;
import dev.moonbridge.api.CommandCompletion;
import dev.moonbridge.api.CommandInvocation;
import dev.moonbridge.api.CommandRegistration;
import dev.moonbridge.api.CommandRegistrationOptions;
import dev.moonbridge.api.CommandSource;
import dev.moonbridge.api.Commands;
import dev.moonbridge.api.PlacementDecision;
import dev.moonbridge.api.PlayerIdentity;
import dev.moonbridge.api.PlayerView;
import dev.moonbridge.api.Players;
import dev.moonbridge.api.Plugin;
import dev.moonbridge.api.PluginContext;
import dev.moonbridge.api.ServerDefinition;
import dev.moonbridge.api.ServerRegistration;
import dev.moonbridge.api.ServerView;
import dev.moonbridge.api.Servers;
import dev.moonbridge.api.TransferResult;
import dev.moonbridge.api.permission.PermissionProvider;
import dev.moonbridge.api.permission.PermissionResult;
import net.kyori.adventure.text.Component;
import dev.moonbridge.core.backend.BackendCatalog;
import dev.moonbridge.core.backend.BackendId;
import dev.moonbridge.core.backend.BackendOwner;
import dev.moonbridge.core.backend.BackendRegistration;
import dev.moonbridge.core.backend.BackendView;
import dev.moonbridge.core.control.BackendChannelTransport;
import dev.moonbridge.core.event.EventDispatcher;
import dev.moonbridge.core.permission.PermissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.jar.JarFile;

/** Owns plugin lifecycles and exposes only the proxy services in the plugin API. */
public final class PluginHost implements AutoCloseable, EventDispatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(PluginHost.class);
    private static final AtomicLong NEXT_HOST_GENERATION = new AtomicLong();
    private static final int MAX_PENDING_COMMAND_COMPLETIONS = 128;
    private static final int MAX_PENDING_COMMAND_EXECUTIONS = 128;
    private static final int MAX_COMPLETION_RESULTS = 100;
    // Leave room for bounded protocol encoding overhead beyond suggestion UTF-8 bodies.
    private static final int MAX_COMPLETION_BYTES = 32_000;
    private static final int MAX_COMPLETION_CANDIDATES_SCANNED = 4096;
    private static final Duration COMMAND_COMPLETION_TIMEOUT = Duration.ofSeconds(1);
    private static final ThreadFactory PLAYER_COMPLETION_THREADS = Thread.ofVirtual()
            .name("moonbridge-plugin-player-", 0).factory();
    private static final Executor PLAYER_COMPLETIONS = task -> PLAYER_COMPLETION_THREADS.newThread(task).start();
    private final BackendCatalog catalog;
    private final Players players;
    private final Duration placementTimeout;
    private final PermissionService permissionService;
    private final Duration shutdownTimeout;
    private final ThreadPoolExecutor callbacks;
    private final ThreadPoolExecutor commandWorkers;
    private final ThreadPoolExecutor backendWorkers;
    private final ConcurrentHashMap<String, RegisteredCommand> commands = new ConcurrentHashMap<>();
    private final Set<PendingCompletion> pendingCompletions = ConcurrentHashMap.newKeySet();
    private final Set<CommandExecution> pendingCommandExecutions = ConcurrentHashMap.newKeySet();
    private final AtomicInteger pendingCompletionCount = new AtomicInteger();
    private final AtomicInteger pendingCommandCount = new AtomicInteger();
    private final LocalMessaging localMessaging;
    private final ScheduledThreadPoolExecutor timer;
    private final AsyncEventDispatcher admissionEvents;
    private final AsyncEventDispatcher notificationEvents;
    private final AsyncEventDispatcher.EventPolicy<AccessDecision> admissionPolicy;
    private final AsyncEventDispatcher.EventPolicy<Void> notificationPolicy;
    private final AtomicLong notificationDrops = new AtomicLong();
    private final List<EventRegistration<?, ?>> eventRegistrations = new ArrayList<>();
    private final List<URLClassLoader> classLoaders = new ArrayList<>();
    private final List<LoadedPlugin> plugins = new ArrayList<>();
    private final Set<PendingPlacement> pendingPlacements = ConcurrentHashMap.newKeySet();
    private volatile State state = State.LOADING;
    private volatile BackendChannelTransport backendChannelTransport;
    private ScheduledFuture<?> notificationDropReporter;
    private InitialPlacementHandler placementHandler;
    private volatile Map<Class<?>, List<EventRegistration<?, ?>>> eventListeners = Map.of();

    public PluginHost(BackendCatalog catalog, Players players, Duration placementTimeout) {
        this(catalog, players, placementTimeout, Duration.ofSeconds(5));
    }

    public PluginHost(BackendCatalog catalog, Players players, Duration placementTimeout, Duration eventTimeout) {
        this(catalog, players, placementTimeout, eventTimeout,
                Math.max(2, Runtime.getRuntime().availableProcessors()), 128, Duration.ofSeconds(10),
                Math.max(2, Runtime.getRuntime().availableProcessors()), 128, 1024);
    }

    PluginHost(BackendCatalog catalog, Players players, Duration placementTimeout,
               int callbackThreads, int callbackQueueCapacity) {
        this(catalog, players, placementTimeout, Duration.ofSeconds(5), callbackThreads, callbackQueueCapacity,
                Duration.ofSeconds(10), Math.max(2, Runtime.getRuntime().availableProcessors()), 128, 1024);
    }

    PluginHost(BackendCatalog catalog, Players players, Duration placementTimeout,
               int callbackThreads, int callbackQueueCapacity, Duration shutdownTimeout) {
        this(catalog, players, placementTimeout, Duration.ofSeconds(5), callbackThreads, callbackQueueCapacity,
                shutdownTimeout, Math.max(2, Runtime.getRuntime().availableProcessors()), 128, 1024);
    }

    PluginHost(BackendCatalog catalog, Players players, Duration placementTimeout, Duration eventTimeout,
               int callbackThreads, int callbackQueueCapacity, int accessThreads, int accessQueueCapacity,
               int maxPendingAccess) {
        this(catalog, players, placementTimeout, eventTimeout, callbackThreads, callbackQueueCapacity,
                Duration.ofSeconds(10), accessThreads, accessQueueCapacity, maxPendingAccess);
    }

    private PluginHost(BackendCatalog catalog, Players players, Duration placementTimeout, Duration eventTimeout,
                       int callbackThreads, int callbackQueueCapacity, Duration shutdownTimeout,
                       int accessThreads, int accessQueueCapacity, int maxPendingAccess) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.players = Objects.requireNonNull(players, "players");
        this.placementTimeout = Objects.requireNonNull(placementTimeout, "placementTimeout");
        Objects.requireNonNull(eventTimeout, "eventTimeout");
        this.permissionService = new PermissionService(eventTimeout);
        this.shutdownTimeout = Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
        if (placementTimeout.isZero() || placementTimeout.isNegative()) {
            throw new IllegalArgumentException("placementTimeout must be positive");
        }
        if (shutdownTimeout.isZero() || shutdownTimeout.isNegative()) {
            throw new IllegalArgumentException("shutdownTimeout must be positive");
        }
        if (callbackThreads < 1 || callbackQueueCapacity < 1) {
            throw new IllegalArgumentException("callback executor sizes must be positive");
        }
        this.callbacks = new ThreadPoolExecutor(callbackThreads, callbackThreads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(callbackQueueCapacity), namedThreads("moonbridge-plugin-callback"),
                new ThreadPoolExecutor.AbortPolicy());
        this.commandWorkers = new ThreadPoolExecutor(callbackThreads, callbackThreads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(callbackQueueCapacity), namedThreads("moonbridge-plugin-command"),
                new ThreadPoolExecutor.AbortPolicy());
        this.backendWorkers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(128), namedThreads("moonbridge-plugin-backend"),
                new ThreadPoolExecutor.AbortPolicy());
        this.timer = new ScheduledThreadPoolExecutor(1, namedThreads("moonbridge-plugin-timeout"));
        this.timer.setRemoveOnCancelPolicy(true);
        this.admissionPolicy = new AsyncEventDispatcher.EventPolicy<>(AccessDecision::allow,
                decision -> decision instanceof AccessDecision.Allowed || decision instanceof AccessDecision.Denied,
                decision -> decision instanceof AccessDecision.Denied, false, (event, failure) -> { },
                maxPendingAccess, false);
        this.notificationPolicy = new AsyncEventDispatcher.EventPolicy<>(() -> null, ignored -> true,
                ignored -> false, true, (event, failure) -> LOGGER.warn("Plugin event listener failed for {}",
                event.getClass().getName(), failure), 129, true);
        this.admissionEvents = new AsyncEventDispatcher(eventTimeout, accessThreads, accessQueueCapacity,
                timer, namedThreads("moonbridge-plugin-admission"));
        this.notificationEvents = new AsyncEventDispatcher(eventTimeout, 1, 128,
                timer, namedThreads("moonbridge-plugin-notification"));
        this.localMessaging = new LocalMessaging(Endpoint.proxy(), new HostMessagingOutbound(), backendWorkers, timer);
    }

    /** Loads every JAR in the directory in filename order through Java's service provider mechanism. */
    public synchronized void loadPlugins(Path directory) throws IOException {
        loadPlugins(directory, null);
    }

    /** Loads only configured provider classes; an empty map enables no external plugins. */
    public synchronized void loadPlugins(Path directory, Map<String, Map<String, String>> enabled) throws IOException {
        requireState(State.LOADING);
        Objects.requireNonNull(directory, "directory");
        if (enabled != null && enabled.isEmpty()) return;
        if (!Files.exists(directory)) {
            if (enabled != null) throw new IOException("Configured plugin directory does not exist: " + directory);
            return;
        }
        if (!Files.isDirectory(directory)) {
            throw new IOException("Plugin path is not a directory: " + directory);
        }
        List<Path> jars;
        try (var files = Files.list(directory)) {
            jars = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        var remaining = enabled == null ? null : new HashSet<>(enabled.keySet());
        for (Path jar : jars) {
            try {
                List<String> selected = enabled == null ? null : declaredProviders(jar).stream()
                        .filter(enabled::containsKey).toList();
                if (selected != null && selected.isEmpty()) continue;
                HostPluginClassLoader loader = new HostPluginClassLoader(new java.net.URL[]{jar.toUri().toURL()},
                        Plugin.class.getClassLoader());
                classLoaders.add(loader);
                if (selected == null) {
                    ServiceLoader.load(Plugin.class, loader).stream().forEach(provider -> {
                        String className = provider.type().getName();
                        loadOne(provider.get(), jar.getFileName() + ":" + className, Map.of(),
                                directory.resolve("data").resolve(className), loader);
                    });
                } else {
                    for (String className : selected) {
                        if (!remaining.remove(className)) {
                            throw new IllegalStateException("Duplicate configured plugin provider: " + className);
                        }
                        Plugin plugin = Class.forName(className, true, loader).asSubclass(Plugin.class)
                                .getConstructor().newInstance();
                        loadOne(plugin, jar.getFileName() + ":" + className, enabled.get(className),
                                directory.resolve("data").resolve(className), loader);
                    }
                }
            } catch (Throwable failure) {
                close();
                throw new IOException("Failed to load plugin JAR " + jar, failure);
            }
        }
        if (remaining != null && !remaining.isEmpty()) {
            close();
            throw new IOException("Configured plugins were not found: " + remaining);
        }
    }

    private static List<String> declaredProviders(Path jar) throws IOException {
        try (JarFile archive = new JarFile(jar.toFile())) {
            var service = archive.getJarEntry("META-INF/services/" + Plugin.class.getName());
            if (service == null) return List.of();
            try (var reader = new BufferedReader(new InputStreamReader(
                    archive.getInputStream(service), StandardCharsets.UTF_8))) {
                var names = new ArrayList<String>();
                String line;
                while ((line = reader.readLine()) != null) {
                    int comment = line.indexOf('#');
                    String name = (comment < 0 ? line : line.substring(0, comment)).trim();
                    if (!name.isEmpty()) names.add(name);
                }
                return names;
            }
        }
    }

    /** Loads plugin instances directly, primarily for embedding and tests. */
    public synchronized void load(List<? extends Plugin> instances) {
        requireState(State.LOADING);
        try {
            for (Plugin plugin : instances) {
                Objects.requireNonNull(plugin, "plugin");
                loadOne(plugin, "instance:" + plugin.getClass().getName() + ":" + plugins.size(), Map.of(),
                        Path.of("plugins").resolve(plugin.getClass().getName()), null);
            }
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    /** Calls onEnable after all providers have loaded and freezes event subscriptions. */
    public synchronized void enable() {
        requireState(State.LOADING);
        try {
            for (LoadedPlugin loaded : plugins) {
                Optional<InitialPlacementHandler> candidate = Objects.requireNonNull(
                        loaded.plugin.initialPlacementHandler(), "initialPlacementHandler result");
                if (candidate.isPresent()) {
                    if (placementHandler != null) {
                        throw new IllegalStateException("Only one plugin may provide initial placement; conflict at "
                                + loaded.owner.id());
                    }
                    placementHandler = candidate.get();
                }
            }
            for (LoadedPlugin loaded : plugins) {
                loaded.enabled = true;
                loaded.context.beginEventRegistration();
                try {
                    loaded.plugin.onEnable();
                } finally {
                    loaded.context.endEventRegistration();
                }
            }
            PermissionProvider selectedPermissions = null;
            String permissionProviderOwner = null;
            for (LoadedPlugin loaded : plugins) {
                Optional<PermissionProvider> candidate = Objects.requireNonNull(
                        loaded.plugin.permissionProvider(), "permissionProvider result");
                if (candidate.isPresent()) {
                    if (selectedPermissions != null) {
                        throw new IllegalStateException("Only one plugin may provide player permissions; conflict at "
                                + loaded.owner.id() + " (already provided by " + permissionProviderOwner + ")");
                    }
                    selectedPermissions = candidate.get();
                    permissionProviderOwner = loaded.owner.id();
                }
            }
            if (selectedPermissions != null) permissionService.configure(selectedPermissions);
            freezeEventRegistrations();
            state = State.ENABLED;
            if (eventListeners.containsKey(ServerConnectedEvent.class)
                    || eventListeners.containsKey(PlayerDisconnectedEvent.class)) {
                notificationDropReporter = timer.scheduleAtFixedRate(this::logNotificationDrops,
                        1, 1, TimeUnit.MINUTES);
            }
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    @Override
    public boolean hasSubscribers(Class<?> eventType) {
        Objects.requireNonNull(eventType, "eventType");
        for (EventRegistration<?, ?> listener : eventListeners.getOrDefault(eventType, List.of())) {
            if (listener.isActive()) return true;
        }
        return false;
    }

    @Override
    public <R> CompletionStage<R> dispatch(Event<R> event) {
        Objects.requireNonNull(event, "event");
        if (state != State.ENABLED) {
            return CompletableFuture.failedFuture(new IllegalStateException("Plugin host is not enabled"));
        }
        List<EventRegistration<?, ?>> registrations = eventListeners.getOrDefault(event.getClass(), List.of());
        if (event instanceof ConnectionAdmissionEvent || event instanceof PlayerAdmissionEvent) {
            return dispatchWithPolicy(admissionEvents, event, registrations, admissionPolicy);
        }
        if (event instanceof ServerConnectedEvent || event instanceof PlayerDisconnectedEvent) {
            CompletionStage<R> delivery = dispatchWithPolicy(notificationEvents, event, registrations, notificationPolicy);
            delivery.whenComplete((ignored, failure) -> {
                if (failure != null) notificationDrops.incrementAndGet();
            });
            return delivery;
        }
        return CompletableFuture.failedFuture(new IllegalArgumentException(
                "Unsupported event type: " + event.getClass().getName()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <R> CompletionStage<R> dispatchWithPolicy(AsyncEventDispatcher dispatcher, Event<R> event,
                                                       List<EventRegistration<?, ?>> registrations,
                                                       AsyncEventDispatcher.EventPolicy<?> policy) {
        return dispatcher.dispatch(event, (List) registrations, (AsyncEventDispatcher.EventPolicy) policy);
    }

    /** Installs the control service used by plugin backend-channel operations. */
    public synchronized void setBackendChannelTransport(BackendChannelTransport transport) {
        if (state == State.CLOSED) throw new IllegalStateException("Plugin host is closed");
        backendChannelTransport = Objects.requireNonNull(transport, "transport");
    }

    /** Host-side dispatcher used by the authenticated control transport. */
    public LocalMessaging localMessaging() { return localMessaging; }

    /** Player permission lifecycle and query service used by the connection/session layer. */
    public PermissionService permissionService() { return permissionService; }

    /** Returns empty when no plugin handles placement; plugin code always runs off the caller's event loop. */
    public CompletionStage<Optional<PlacementDecision>> placeInitial(PlayerView player) {
        Objects.requireNonNull(player, "player");
        final InitialPlacementHandler handler;
        final CompletableFuture<Optional<PlacementDecision>> result;
        final AtomicBoolean decided;
        final AtomicReference<Runnable> callback;
        final AtomicReference<CompletableFuture<PlacementDecision>> pluginStage;
        final PendingPlacement request;
        final ScheduledFuture<?> timeoutTask;
        synchronized (this) {
            if (state != State.ENABLED) {
                return CompletableFuture.failedFuture(new IllegalStateException("Plugin host is not enabled"));
            }
            handler = placementHandler;
            if (handler == null) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            result = new CompletableFuture<>();
            decided = new AtomicBoolean();
            callback = new AtomicReference<>();
            pluginStage = new AtomicReference<>();
            request = new PendingPlacement(result, decided);
            pendingPlacements.add(request);
            try {
                timeoutTask = timer.schedule(
                        () -> {
                            if (!decided.compareAndSet(false, true)) return;
                            result.completeExceptionally(new PlacementTimeoutException(placementTimeout));
                        }, placementTimeout.toNanos(), TimeUnit.NANOSECONDS);
            } catch (RuntimeException failure) {
                pendingPlacements.remove(request);
                return CompletableFuture.failedFuture(failure);
            }
        }
        result.whenComplete((ignored, failure) -> {
            decided.set(true);
            timeoutTask.cancel(false);
            pendingPlacements.remove(request);
            Runnable queued = callback.get();
            if (queued != null) callbacks.remove(queued);
            CompletableFuture<PlacementDecision> stage = pluginStage.get();
            if (stage != null && !stage.isDone()) stage.cancel(false);
        });
        Runnable invocation = () -> {
            if (decided.get()) {
                return;
            }
            try {
                CompletionStage<PlacementDecision> stage = Objects.requireNonNull(
                        handler.place(player, snapshotServers()), "placement handler stage");
                CompletableFuture<PlacementDecision> cancellable = stage.toCompletableFuture();
                pluginStage.set(cancellable);
                if (result.isDone() && !cancellable.isDone()) cancellable.cancel(false);
                stage.whenComplete((decision, failure) -> {
                    if (failure != null) {
                        if (!decided.compareAndSet(false, true)) return;
                        result.completeExceptionally(failure);
                    } else if (decision == null) {
                        if (!decided.compareAndSet(false, true)) return;
                        IllegalStateException invalid = new IllegalStateException("Placement handler returned null");
                        result.completeExceptionally(invalid);
                    } else {
                        if (decided.compareAndSet(false, true)) result.complete(Optional.of(decision));
                    }
                });
            } catch (Throwable failure) {
                if (!decided.compareAndSet(false, true)) return;
                result.completeExceptionally(failure);
            }
        };
        callback.set(invocation);
        try {
            if (!decided.get()) {
                callbacks.execute(invocation);
                if (decided.get()) callbacks.remove(invocation);
            }
        } catch (RejectedExecutionException overloaded) {
            if (decided.compareAndSet(false, true)) {
                result.completeExceptionally(new PluginOverloadedException(overloaded));
            }
        }
        return result;
    }

    /** Returns up to 100 registered roots matching a case-insensitive prefix without a leading slash. */
    public List<String> commandNames(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        if (state != State.ENABLED) return List.of();
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
    public List<String> commandNames(PlayerView player, String prefix) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(prefix, "prefix");
        if (state != State.ENABLED) return List.of();
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
    public boolean commandVisible(PlayerView player, String suggestion) {
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
    public Optional<CompletionStage<List<String>>> completeCommand(PlayerView player, String input) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(input, "input");
        if (state != State.ENABLED || input.length() < 2 || input.charAt(0) != '/') return Optional.empty();
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
        synchronized (this) {
            if (state != State.ENABLED || commands.get(name) != registered || !registered.context.active) {
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
        if (state != State.ENABLED || !pending.registered.context.active
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
            PLAYER_COMPLETIONS.execute(() -> pluginStage.cancel(true));
        }
        if (failure != null) pending.result.completeExceptionally(failure);
        else {
            List<String> safeSuggestions = Objects.requireNonNull(suggestions, "suggestions");
            if (!authorized(pending.player, pending.registered)) safeSuggestions = List.of();
            pending.result.complete(safeSuggestions);
        }
    }

    /** Claims only known root commands; caller retains and forwards every other chat frame. */
    public boolean dispatchCommand(PlayerView player, String message, Consumer<Component> reply) {
        return dispatchCommand(player, message, reply, () -> true);
    }

    /** Admission is checked only after the command name is known, preserving unknown-command passthrough. */
    public boolean dispatchCommand(PlayerView player, String message, Consumer<Component> reply,
                                   BooleanSupplier admission) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(reply, "reply");
        Objects.requireNonNull(admission, "admission");
        if (state != State.ENABLED || !message.startsWith("/") || message.length() < 2) return false;
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
                if (state != State.ENABLED || !registered.context.active || commands.get(name) != registered) {
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
    public boolean dispatchConsoleCommand(String input, Consumer<Component> reply) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(reply, "reply");
        if (state != State.ENABLED) return false;
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
                if (state != State.ENABLED || !registered.context.active || commands.get(name) != registered) {
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

    private synchronized CompletionStage<Boolean> executeCommand(PluginContextImpl caller, CommandSource source,
                                                                  String input) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(input, "command");
        synchronized (caller) { caller.requireActive(); }
        if (state != State.ENABLED) {
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
                    if (state != State.ENABLED || !registered.context.active || commands.get(name) != registered) {
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

    private synchronized CommandExecution beginCommandExecution(RegisteredCommand command,
                                                                 CompletableFuture<Boolean> result) {
        if (state != State.ENABLED) return null;
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

    @Override
    public synchronized void close() {
        if (state == State.CLOSED) {
            return;
        }
        state = State.CLOSED;
        permissionService.close();
        for (CommandExecution execution : pendingCommandExecutions) {
            execution.finish(new IllegalStateException("Plugin host closed"), null, null);
        }
        // Settle pending admission chains before revoking listeners. Otherwise a queued
        // chain can skip the revoked subscriptions and incorrectly finish with allow().
        admissionEvents.close();
        notificationEvents.close();
        localMessaging.close();
        if (notificationDropReporter != null) notificationDropReporter.cancel(false);
        logNotificationDrops();
        for (LoadedPlugin loaded : plugins) loaded.context.revokeEventSubscriptions();
        for (PendingPlacement request : pendingPlacements) {
            request.decided().set(true);
            request.result().completeExceptionally(new IllegalStateException("Plugin host closed"));
        }
        for (PendingCompletion pending : pendingCompletions) {
            finishCompletion(pending, null, new IllegalStateException("Plugin host closed"), true);
        }
        callbacks.shutdownNow();
        commandWorkers.shutdownNow();
        backendWorkers.shutdownNow();
        timer.shutdownNow();
        long deadline = System.nanoTime() + shutdownTimeout.toNanos();
        for (int index = plugins.size() - 1; index >= 0; index--) {
            LoadedPlugin loaded = plugins.get(index);
            if (loaded.enabled) {
                disableWithinDeadline(loaded, deadline);
            }
            loaded.context.deactivateAndRemove();
        }
        for (URLClassLoader loader : classLoaders) {
            try {
                loader.close();
            } catch (IOException failure) {
                LOGGER.warn("Could not close plugin class loader", failure);
            }
        }
        classLoaders.clear();
        placementHandler = null;
        eventListeners = Map.of();
        eventRegistrations.clear();
        backendChannelTransport = null;
    }

    private void disableWithinDeadline(LoadedPlugin loaded, long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            LOGGER.warn("Skipping onDisable for plugin {} after shutdown deadline", loaded.owner.id());
            return;
        }
        FutureTask<Void> stop = new FutureTask<>(() -> {
            loaded.plugin.onDisable();
            return null;
        });
        Thread.ofVirtual().name("moonbridge-plugin-disable-" + loaded.owner.id()).start(stop);
        try {
            stop.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            stop.cancel(true);
            LOGGER.warn("Plugin {} onDisable exceeded shutdown deadline", loaded.owner.id());
        } catch (ExecutionException failure) {
            LOGGER.warn("Plugin {} failed during onDisable", loaded.owner.id(), failure.getCause());
        } catch (InterruptedException interrupted) {
            stop.cancel(true);
            Thread.currentThread().interrupt();
        }
    }

    private void loadOne(Plugin plugin, String id, Map<String, String> settings, Path dataDirectory,
                         HostPluginClassLoader pluginClassLoader) {
        long generation = NEXT_HOST_GENERATION.updateAndGet(value -> {
            if (value == Long.MAX_VALUE) {
                throw new IllegalStateException("Plugin host generation exhausted");
            }
            return value + 1;
        });
        BackendOwner owner = new BackendOwner(id, generation);
        PluginContextImpl context = new PluginContextImpl(owner, settings, dataDirectory, pluginClassLoader);
        LoadedPlugin loaded = new LoadedPlugin(plugin, owner, context);
        plugins.add(loaded);
        context.beginEventRegistration();
        try {
            plugin.onLoad(context);
        } catch (Throwable failure) {
            context.deactivateAndRemove();
            plugins.remove(loaded);
            throw new PluginLoadException(id, failure);
        } finally {
            context.endEventRegistration();
        }
    }

    private synchronized void freezeEventRegistrations() {
        var frozen = new java.util.LinkedHashMap<Class<?>, List<EventRegistration<?, ?>>>();
        for (EventRegistration<?, ?> registration : eventRegistrations) {
            frozen.computeIfAbsent(registration.eventType(), ignored -> new ArrayList<>()).add(registration);
        }
        frozen.replaceAll((ignored, registrations) -> List.copyOf(registrations));
        eventListeners = Map.copyOf(frozen);
    }

    private <R, E extends Event<R>> EventSubscription registerEvent(
            PluginContextImpl context, Class<E> eventType, EventListener<? super E, R> listener) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(listener, "listener");
        if (eventType != ConnectionAdmissionEvent.class && eventType != PlayerAdmissionEvent.class
                && eventType != ServerConnectedEvent.class && eventType != PlayerDisconnectedEvent.class)
            throw new IllegalArgumentException("Unsupported event type: " + eventType.getName());
        requireEventRegistrationState();
        synchronized (this) {
            requireEventRegistrationState();
            synchronized (context) {
                context.requireEventRegistrationOpen();
                EventRegistration<E, R> registration = new EventRegistration<>(context, eventType, listener);
                eventRegistrations.add(registration);
                context.eventSubscriptions.add(registration);
                return registration;
            }
        }
    }

    private void requireEventRegistrationState() {
        if (state != State.LOADING) {
            throw new IllegalStateException("Event subscriptions are only allowed during onLoad or onEnable");
        }
    }

    private void logNotificationDrops() {
        long count = notificationDrops.getAndSet(0);
        if (count != 0) {
            LOGGER.warn("Dropped {} plugin notification event(s) after timeout, failure, or queue overflow", count);
        }
    }

    private List<ServerView> snapshotServers() {
        return catalog.snapshot().stream().map(PluginHost::toServerView).toList();
    }

    private static ServerView toServerView(BackendView view) {
        return new ServerView(view.handle().id().value(), view.address(), view.tags(), view.metadata());
    }

    private void requireState(State expected) {
        if (state != expected) {
            throw new IllegalStateException("Plugin host state is " + state + "; expected " + expected);
        }
    }

    private static ThreadFactory namedThreads(String prefix) {
        AtomicLong number = new AtomicLong();
        return task -> {
            Thread thread = new Thread(task, prefix + "-" + number.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private enum State { LOADING, ENABLED, CLOSED }

    private record PendingPlacement(CompletableFuture<Optional<PlacementDecision>> result,
                                    AtomicBoolean decided) { }

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

    private final class EventRegistration<E extends Event<R>, R>
            implements EventSubscription, AsyncEventDispatcher.EventHandler<R> {
        private final PluginContextImpl context;
        private final Class<E> eventType;
        private final EventListener<? super E, R> listener;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private EventRegistration(PluginContextImpl context, Class<E> eventType,
                                  EventListener<? super E, R> listener) {
            this.context = context;
            this.eventType = eventType;
            this.listener = listener;
        }

        private Class<E> eventType() { return eventType; }
        @Override public boolean active() { return isActive(); }
        @Override public CompletionStage<R> handle(Event<?> event) {
            return listener.onEvent(eventType.cast(event));
        }
        private boolean isActive() { return active.get() && context.active; }
        @Override public void close() { active.set(false); }
    }

    private static final class LoadedPlugin {
        private final Plugin plugin;
        private final BackendOwner owner;
        private final PluginContextImpl context;
        private boolean enabled;

        private LoadedPlugin(Plugin plugin, BackendOwner owner, PluginContextImpl context) {
            this.plugin = plugin;
            this.owner = owner;
            this.context = context;
        }
    }

    /** URLClassLoader with a supported, narrowly-scoped hook for plugin-owned extension libraries. */
    private static final class HostPluginClassLoader extends URLClassLoader {
        private final Set<Path> libraries = new HashSet<>();

        private HostPluginClassLoader(java.net.URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        private synchronized void addLibrary(Path requested, Path pluginDataDirectory) throws IOException {
            Objects.requireNonNull(requested, "jar");
            Path absoluteDataDirectory = pluginDataDirectory.toAbsolutePath().normalize();
            Files.createDirectories(absoluteDataDirectory);
            Path realDataDirectory = absoluteDataDirectory.toRealPath();
            Path absoluteRequest = requested.toAbsolutePath().normalize();
            Path candidate = requested.isAbsolute() || absoluteRequest.startsWith(absoluteDataDirectory)
                    ? absoluteRequest : realDataDirectory.resolve(requested);
            Path realJar = candidate.toRealPath();
            if (!realJar.startsWith(realDataDirectory)) {
                throw new IOException("Plugin library must be inside its data directory: " + requested);
            }
            if (!Files.isRegularFile(realJar) || !realJar.getFileName().toString().toLowerCase(Locale.ROOT)
                    .endsWith(".jar")) {
                throw new IOException("Plugin library must be a regular JAR file: " + requested);
            }
            try (JarFile ignored = new JarFile(realJar.toFile())) {
                // Opening the archive validates that it is a readable JAR before it enters the class path.
            }
            if (libraries.add(realJar)) addURL(realJar.toUri().toURL());
        }
    }

    private final class PluginContextImpl implements PluginContext {
        private final BackendOwner owner;
        private final Messaging pluginMessaging;
        private final Players pluginPlayers;
        private final PluginServers servers;
        private final Commands pluginCommands;
        private final Events pluginEvents;
        private final dev.moonbridge.api.permission.Permissions pluginPermissions;
        private final Logger logger;
        private final Map<String, String> settings;
        private final Path dataDirectory;
        private final HostPluginClassLoader pluginClassLoader;
        private final Set<EventRegistration<?, ?>> eventSubscriptions = new HashSet<>();
        private volatile boolean active = true;
        private boolean eventRegistrationOpen;

        private PluginContextImpl(BackendOwner owner, Map<String, String> settings, Path dataDirectory,
                                  HostPluginClassLoader pluginClassLoader) {
            this.owner = owner;
            this.pluginMessaging = localMessaging.openScope(owner.id());
            this.pluginPlayers = new PluginPlayers(this);
            this.servers = new PluginServers(this);
            this.pluginCommands = new PluginCommands(this);
            this.pluginEvents = new PluginEvents(this);
            this.pluginPermissions = new dev.moonbridge.api.permission.Permissions() {
                @Override public PermissionResult check(PlayerIdentity identity, String node) {
                    synchronized (PluginContextImpl.this) {
                        if (!active) return PermissionResult.UNAVAILABLE;
                        return permissionService.check(identity, node);
                    }
                }
                @Override public PermissionResult check(PlayerIdentity identity, String node,
                                                        dev.moonbridge.api.permission.PermissionContext permissionContext) {
                    synchronized (PluginContextImpl.this) {
                        if (!active) return PermissionResult.UNAVAILABLE;
                        return permissionService.check(identity, node, permissionContext);
                    }
                }
            };
            this.logger = LoggerFactory.getLogger("plugin." + owner.id());
            this.settings = Map.copyOf(settings);
            this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
            this.pluginClassLoader = pluginClassLoader;
        }

        @Override public Players players() { return pluginPlayers; }
        @Override public Servers servers() { return servers; }
        @Override public Commands commands() { return pluginCommands; }
        @Override public Events events() { return pluginEvents; }
        @Override public Messaging messaging() { return pluginMessaging; }
        @Override public Logger logger() { return logger; }
        @Override public dev.moonbridge.api.permission.Permissions permissions() { return pluginPermissions; }
        @Override public Path dataDirectory() {
            synchronized (this) { requireActive(); return dataDirectory; }
        }
        @Override public void addLibrary(Path jar) throws IOException {
            synchronized (this) {
                requireActive();
                if (pluginClassLoader == null) {
                    throw new UnsupportedOperationException("Dynamic libraries require a JAR-loaded plugin");
                }
                pluginClassLoader.addLibrary(jar, dataDirectory);
            }
        }
        @Override public Map<String, String> settings() { return settings; }

        private synchronized void deactivateAndRemove() {
            active = false;
            eventRegistrationOpen = false;
            pluginMessaging.close();
            revokeEventSubscriptions();
            for (RegisteredCommand command : commands.values()) {
                if (command.context == this) {
                    for (PendingCompletion pending : command.pendingCompletions) {
                        finishCompletion(pending, null, new IllegalStateException("Plugin unloaded"), true);
                    }
                }
            }
            commands.entrySet().removeIf(entry -> entry.getValue().context == this);
            catalog.removeOwner(owner);
        }

        private synchronized void revokeEventSubscriptions() {
            eventRegistrationOpen = false;
            eventSubscriptions.forEach(EventRegistration::close);
            eventSubscriptions.clear();
        }

        private synchronized void beginEventRegistration() {
            if (!active) throw new IllegalStateException("Plugin context is inactive");
            eventRegistrationOpen = true;
        }

        private synchronized void endEventRegistration() { eventRegistrationOpen = false; }

        private synchronized void requireEventRegistrationOpen() {
            requireActive();
            if (!eventRegistrationOpen || state != State.LOADING) {
                throw new IllegalStateException("Event subscriptions are only allowed during onLoad or onEnable");
            }
        }

        private void requireActive() {
            if (!active) throw new IllegalStateException("Plugin context is inactive");
        }
    }

    private final class PluginEvents implements Events {
        private final PluginContextImpl context;

        private PluginEvents(PluginContextImpl context) { this.context = context; }

        @Override public <R, E extends Event<R>> EventSubscription subscribe(
                Class<E> eventType, EventListener<E, R> listener) {
            return registerEvent(context, eventType, listener);
        }
    }

    private final class HostMessagingOutbound implements LocalMessaging.Outbound {
        @Override public CompletionStage<SendResult> send(Message message) {
            if (!Endpoint.proxy().equals(message.source()) || message.kind() != MessageKind.EVENT
                    || message.target() == null) {
                return CompletableFuture.failedFuture(new MessagingException(
                        MessagingException.Code.REJECTED, "invalid proxy message send"));
            }
            if (message.target().isProxy()) {
                return CompletableFuture.completedFuture(localMessaging.receiveEvent(message));
            }
            BackendChannelTransport transport = backendChannelTransport;
            if (transport == null) return CompletableFuture.completedFuture(SendResult.NOT_CONNECTED);
            try {
                return Objects.requireNonNull(transport.send(message), "transport send stage");
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        @Override public CompletionStage<Message> request(Message message, Duration timeout) {
            if (!Endpoint.proxy().equals(message.source()) || message.kind() != MessageKind.REQUEST
                    || message.target() == null) {
                return CompletableFuture.failedFuture(new MessagingException(
                        MessagingException.Code.REJECTED, "invalid proxy message request"));
            }
            if (message.target().isProxy()) return localMessaging.receiveRequest(message, timeout);
            BackendChannelTransport transport = backendChannelTransport;
            if (transport == null) return CompletableFuture.failedFuture(new MessagingException(
                    MessagingException.Code.NOT_CONNECTED, "backend control transport is not available"));
            try {
                return Objects.requireNonNull(transport.request(message, timeout), "transport request stage");
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        @Override public CompletionStage<PublishResult> publish(Message event) {
            if (!Endpoint.proxy().equals(event.source()) || event.kind() != MessageKind.EVENT
                    || event.target() != null) {
                return CompletableFuture.failedFuture(new MessagingException(
                        MessagingException.Code.REJECTED, "invalid proxy event publication"));
            }
            BackendChannelTransport transport = backendChannelTransport;
            if (transport == null) return CompletableFuture.completedFuture(new PublishResult(
                    event.id(), Map.of(Endpoint.proxy(), localMessaging.receiveEvent(event))));
            final CompletionStage<PublishResult> remote;
            try {
                remote = Objects.requireNonNull(transport.publish(event), "transport publish stage");
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
            CompletableFuture<PublishResult> combinedResult = new CompletableFuture<>();
            remote.whenComplete((result, failure) -> {
                if (combinedResult.isCancelled()) return;
                if (failure != null) {
                    combinedResult.completeExceptionally(failure);
                    return;
                }
                if (!event.id().equals(result.messageId()) || result.results().containsKey(Endpoint.proxy())) {
                    combinedResult.completeExceptionally(new MessagingException(MessagingException.Code.PROTOCOL_ERROR,
                            "transport returned an invalid publish result"));
                    return;
                }
                Map<Endpoint, SendResult> combined = new java.util.LinkedHashMap<>();
                combined.put(Endpoint.proxy(), localMessaging.receiveEvent(event));
                combined.putAll(result.results());
                combinedResult.complete(new PublishResult(event.id(), combined));
            });
            combinedResult.whenComplete((ignored, failure) -> {
                if (combinedResult.isCancelled()) remote.toCompletableFuture().cancel(false);
            });
            return combinedResult;
        }

    }

    private final class PluginCommands implements Commands {
        private final PluginContextImpl context;

        private PluginCommands(PluginContextImpl context) { this.context = context; }

        @Override public CommandRegistration register(String name, CommandHandler handler, CommandCompleter completer) {
            return register(name, CommandRegistrationOptions.defaults(), handler, completer);
        }

        @Override public CommandRegistration register(String name, CommandRegistrationOptions options,
                                                       CommandHandler handler, CommandCompleter completer) {
            Objects.requireNonNull(handler, "handler");
            return registerAsync(name, options, invocation -> {
                handler.execute(invocation);
                return CompletableFuture.completedFuture(null);
            }, completer);
        }

        @Override public CommandRegistration registerAsync(String name, CommandRegistrationOptions options,
                                                            AsyncCommandHandler handler,
                                                            CommandCompleter completer) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(options, "options");
            Objects.requireNonNull(handler, "handler");
            String normalized = name.toLowerCase(Locale.ROOT);
            if (!normalized.matches("[a-z0-9_.:-]{1,32}")) {
                throw new IllegalArgumentException("invalid command name: " + name);
            }
            synchronized (context) {
                context.requireActive();
                RegisteredCommand registration = new RegisteredCommand(normalized, context, handler, completer,
                        options);
                if (commands.putIfAbsent(normalized, registration) != null) {
                    throw new IllegalArgumentException("Command already registered: /" + normalized);
                }
                return () -> {
                    synchronized (PluginHost.this) {
                        if (commands.remove(normalized, registration)) {
                            for (PendingCompletion pending : registration.pendingCompletions) {
                                finishCompletion(pending, null, new IllegalStateException("Command unregistered"), true);
                            }
                        }
                    }
                };
            }
        }

        @Override public CompletionStage<Boolean> execute(CommandSource source, String command) {
            return PluginHost.this.executeCommand(context, source, command);
        }
    }

    private final class PluginPlayers implements Players {
        private final PluginContextImpl context;

        private PluginPlayers(PluginContextImpl context) {
            this.context = context;
        }

        @Override public Optional<PlayerView> find(PlayerIdentity identity) {
            synchronized (context) {
                context.requireActive();
                return players.find(identity);
            }
        }

        @Override public List<PlayerView> online() {
            synchronized (context) {
                context.requireActive();
                return players.online();
            }
        }

        @Override public CompletionStage<TransferResult> transfer(PlayerIdentity identity, String backendName) {
            synchronized (context) {
                context.requireActive();
                // CompletableFuture runs dependent callbacks on its completion thread by default.
                // Do not let plugin callbacks execute on the player's Netty event loop.
                return players.transfer(identity, backendName)
                        .whenCompleteAsync((ignored, failure) -> { }, PLAYER_COMPLETIONS);
            }
        }

        @Override public CompletionStage<MessageResult> sendMessage(PlayerIdentity identity, Component message) {
            synchronized (context) {
                context.requireActive();
                return players.sendMessage(identity, message)
                        .whenCompleteAsync((ignored, failure) -> { }, PLAYER_COMPLETIONS);
            }
        }

        @Override public CompletionStage<DisconnectResult> disconnect(PlayerIdentity identity, Component reason) {
            synchronized (context) {
                context.requireActive();
                return players.disconnect(identity, reason)
                        .whenCompleteAsync((ignored, failure) -> { }, PLAYER_COMPLETIONS);
            }
        }
    }

    private final class PluginServers implements Servers {
        private final PluginContextImpl context;
        private final BackendOwner owner;

        private PluginServers(PluginContextImpl context) {
            this.context = context;
            this.owner = context.owner;
        }

        @Override
        public Optional<ServerView> find(String backendName) {
            synchronized (context) {
                context.requireActive();
                Objects.requireNonNull(backendName, "backendName");
                return catalog.find(new BackendId(backendName)).map(PluginHost::toServerView);
            }
        }

        @Override
        public List<ServerView> all() {
            synchronized (context) {
                context.requireActive();
                return snapshotServers();
            }
        }

        @Override
        public ServerRegistration register(ServerDefinition definition) {
            synchronized (context) {
                context.requireActive();
                Objects.requireNonNull(definition, "definition");
                BackendId id = new BackendId(definition.name());
                BackendRegistration registration = new BackendRegistration(id, owner, definition.address(),
                        definition.tags(), definition.metadata());
                BackendView view = catalog.register(registration);
                return new PluginServerRegistration(view.handle());
            }
        }

        private final class PluginServerRegistration implements ServerRegistration {
            private final dev.moonbridge.core.backend.BackendHandle handle;
            private boolean active = true;

            private PluginServerRegistration(dev.moonbridge.core.backend.BackendHandle handle) {
                this.handle = handle;
            }

            @Override
            public synchronized void update(ServerDefinition replacement) {
                ensureActive();
                Objects.requireNonNull(replacement, "definition");
                if (!replacement.name().equals(handle.id().value())) {
                    throw new IllegalArgumentException("An update must keep backend name " + handle.id().value());
                }
                BackendRegistration next = new BackendRegistration(handle.id(), owner, replacement.address(),
                        replacement.tags(), replacement.metadata());
                if (catalog.update(handle, next).isEmpty()) {
                    active = false;
                    throw new IllegalStateException("Backend registration is stale: " + handle.id().value());
                }
            }

            @Override
            public synchronized void unregister() {
                ensureActive();
                if (!catalog.remove(handle)) {
                    active = false;
                    throw new IllegalStateException("Backend registration is stale: " + handle.id().value());
                }
                active = false;
            }

            private void ensureActive() {
                if (!active || !PluginServers.this.context.active) {
                    throw new IllegalStateException("Backend registration is stale: " + handle.id().value());
                }
            }
        }
    }
}

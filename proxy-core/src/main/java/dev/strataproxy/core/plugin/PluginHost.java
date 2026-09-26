package dev.strataproxy.core.plugin;

import dev.strataproxy.api.AccessDecision;
import dev.strataproxy.api.MessageResult;
import dev.strataproxy.api.DisconnectResult;
import dev.strataproxy.api.event.ConnectionAdmissionEvent;
import dev.strataproxy.api.event.Event;
import dev.strataproxy.api.event.EventListener;
import dev.strataproxy.api.event.EventSubscription;
import dev.strataproxy.api.event.Events;
import dev.strataproxy.api.event.PlayerAdmissionEvent;
import dev.strataproxy.api.event.PlayerDisconnectedEvent;
import dev.strataproxy.api.event.ServerConnectedEvent;
import dev.strataproxy.api.InitialPlacementHandler;
import dev.strataproxy.api.CommandHandler;
import dev.strataproxy.api.CommandInvocation;
import dev.strataproxy.api.CommandRegistration;
import dev.strataproxy.api.Commands;
import dev.strataproxy.api.PlacementDecision;
import dev.strataproxy.api.PlayerIdentity;
import dev.strataproxy.api.PlayerView;
import dev.strataproxy.api.Players;
import dev.strataproxy.api.Plugin;
import dev.strataproxy.api.PluginContext;
import dev.strataproxy.api.ServerDefinition;
import dev.strataproxy.api.ServerRegistration;
import dev.strataproxy.api.ServerView;
import dev.strataproxy.api.Servers;
import dev.strataproxy.api.TransferResult;
import dev.strataproxy.core.backend.BackendCatalog;
import dev.strataproxy.core.backend.BackendId;
import dev.strataproxy.core.backend.BackendOwner;
import dev.strataproxy.core.backend.BackendRegistration;
import dev.strataproxy.core.backend.BackendView;
import dev.strataproxy.core.event.EventDispatcher;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.jar.JarFile;

/** Owns plugin lifecycles and exposes only the proxy services in the plugin API. */
public final class PluginHost implements AutoCloseable, EventDispatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(PluginHost.class);
    private static final AtomicLong NEXT_HOST_GENERATION = new AtomicLong();
    private static final ThreadFactory PLAYER_COMPLETION_THREADS = Thread.ofVirtual()
            .name("strataproxy-plugin-player-", 0).factory();
    private static final Executor PLAYER_COMPLETIONS = task -> PLAYER_COMPLETION_THREADS.newThread(task).start();
    private final BackendCatalog catalog;
    private final Players players;
    private final Duration placementTimeout;
    private final Duration shutdownTimeout;
    private final ThreadPoolExecutor callbacks;
    private final ThreadPoolExecutor commandWorkers;
    private final ConcurrentHashMap<String, RegisteredCommand> commands = new ConcurrentHashMap<>();
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
                new ArrayBlockingQueue<>(callbackQueueCapacity), namedThreads("strataproxy-plugin-callback"),
                new ThreadPoolExecutor.AbortPolicy());
        this.commandWorkers = new ThreadPoolExecutor(callbackThreads, callbackThreads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(callbackQueueCapacity), namedThreads("strataproxy-plugin-command"),
                new ThreadPoolExecutor.AbortPolicy());
        this.timer = new ScheduledThreadPoolExecutor(1, namedThreads("strataproxy-plugin-timeout"));
        this.timer.setRemoveOnCancelPolicy(true);
        this.admissionPolicy = new AsyncEventDispatcher.EventPolicy<>(AccessDecision::allow,
                decision -> decision instanceof AccessDecision.Allowed || decision instanceof AccessDecision.Denied,
                decision -> decision instanceof AccessDecision.Denied, false, (event, failure) -> { },
                maxPendingAccess, false);
        this.notificationPolicy = new AsyncEventDispatcher.EventPolicy<>(() -> null, ignored -> true,
                ignored -> false, true, (event, failure) -> LOGGER.warn("Plugin event listener failed for {}",
                event.getClass().getName(), failure), 129, true);
        this.admissionEvents = new AsyncEventDispatcher(eventTimeout, accessThreads, accessQueueCapacity,
                timer, namedThreads("strataproxy-plugin-admission"));
        this.notificationEvents = new AsyncEventDispatcher(eventTimeout, 1, 128,
                timer, namedThreads("strataproxy-plugin-notification"));
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
                URLClassLoader loader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()},
                        Plugin.class.getClassLoader());
                classLoaders.add(loader);
                if (selected == null) {
                    ServiceLoader.load(Plugin.class, loader).stream().forEach(provider -> {
                        String className = provider.type().getName();
                        loadOne(provider.get(), jar.getFileName() + ":" + className, Map.of());
                    });
                } else {
                    for (String className : selected) {
                        if (!remaining.remove(className)) {
                            throw new IllegalStateException("Duplicate configured plugin provider: " + className);
                        }
                        Plugin plugin = Class.forName(className, true, loader).asSubclass(Plugin.class)
                                .getConstructor().newInstance();
                        loadOne(plugin, jar.getFileName() + ":" + className, enabled.get(className));
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
                loadOne(plugin, "instance:" + plugin.getClass().getName() + ":" + plugins.size(), Map.of());
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

    /** Claims only known root commands; caller retains and forwards every other chat frame. */
    public boolean dispatchCommand(PlayerView player, String message, Consumer<String> reply) {
        return dispatchCommand(player, message, reply, () -> true);
    }

    /** Admission is checked only after the command name is known, preserving unknown-command passthrough. */
    public boolean dispatchCommand(PlayerView player, String message, Consumer<String> reply,
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
        String arguments = end == message.length() ? "" : message.substring(end).stripLeading();
        CommandInvocation invocation = new CommandInvocation(player, name, arguments,
                text -> { if (registered.context.active) reply.accept(text); });
        try {
            commandWorkers.execute(() -> {
                if (state != State.ENABLED || !registered.context.active || commands.get(name) != registered) return;
                try {
                    registered.handler.execute(invocation);
                } catch (Throwable failure) {
                    LOGGER.warn("Plugin {} command /{} failed", registered.context.owner.id(), name, failure);
                    invocation.reply("Proxy command failed.");
                }
            });
        } catch (RejectedExecutionException overloaded) {
            reply.accept("Proxy command service is busy. Please try again.");
        }
        return true;
    }

    @Override
    public synchronized void close() {
        if (state == State.CLOSED) {
            return;
        }
        state = State.CLOSED;
        // Settle pending admission chains before revoking listeners. Otherwise a queued
        // chain can skip the revoked subscriptions and incorrectly finish with allow().
        admissionEvents.close();
        notificationEvents.close();
        if (notificationDropReporter != null) notificationDropReporter.cancel(false);
        logNotificationDrops();
        for (LoadedPlugin loaded : plugins) loaded.context.revokeEventSubscriptions();
        for (PendingPlacement request : pendingPlacements) {
            request.decided().set(true);
            request.result().completeExceptionally(new IllegalStateException("Plugin host closed"));
        }
        callbacks.shutdownNow();
        commandWorkers.shutdownNow();
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
        Thread.ofVirtual().name("strataproxy-plugin-disable-" + loaded.owner.id()).start(stop);
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

    private void loadOne(Plugin plugin, String id, Map<String, String> settings) {
        long generation = NEXT_HOST_GENERATION.updateAndGet(value -> {
            if (value == Long.MAX_VALUE) {
                throw new IllegalStateException("Plugin host generation exhausted");
            }
            return value + 1;
        });
        BackendOwner owner = new BackendOwner(id, generation);
        PluginContextImpl context = new PluginContextImpl(owner, settings);
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

    private record RegisteredCommand(String name, PluginContextImpl context, CommandHandler handler) { }

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

    private final class PluginContextImpl implements PluginContext {
        private final BackendOwner owner;
        private final Players pluginPlayers;
        private final PluginServers servers;
        private final Commands pluginCommands;
        private final Events pluginEvents;
        private final Logger logger;
        private final Map<String, String> settings;
        private final Set<EventRegistration<?, ?>> eventSubscriptions = new HashSet<>();
        private volatile boolean active = true;
        private boolean eventRegistrationOpen;

        private PluginContextImpl(BackendOwner owner, Map<String, String> settings) {
            this.owner = owner;
            this.pluginPlayers = new PluginPlayers(this);
            this.servers = new PluginServers(this);
            this.pluginCommands = new PluginCommands(this);
            this.pluginEvents = new PluginEvents(this);
            this.logger = LoggerFactory.getLogger("plugin." + owner.id());
            this.settings = Map.copyOf(settings);
        }

        @Override public Players players() { return pluginPlayers; }
        @Override public Servers servers() { return servers; }
        @Override public Commands commands() { return pluginCommands; }
        @Override public Events events() { return pluginEvents; }
        @Override public Logger logger() { return logger; }
        @Override public Map<String, String> settings() { return settings; }

        private synchronized void deactivateAndRemove() {
            active = false;
            eventRegistrationOpen = false;
            revokeEventSubscriptions();
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

    private final class PluginCommands implements Commands {
        private final PluginContextImpl context;

        private PluginCommands(PluginContextImpl context) { this.context = context; }

        @Override public CommandRegistration register(String name, CommandHandler handler) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(handler, "handler");
            String normalized = name.toLowerCase(Locale.ROOT);
            if (!normalized.matches("[a-z0-9_.:-]{1,32}")) {
                throw new IllegalArgumentException("invalid command name: " + name);
            }
            synchronized (context) {
                context.requireActive();
                RegisteredCommand registration = new RegisteredCommand(normalized, context, handler);
                if (commands.putIfAbsent(normalized, registration) != null) {
                    throw new IllegalArgumentException("Command already registered: /" + normalized);
                }
                return () -> commands.remove(normalized, registration);
            }
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

        @Override public CompletionStage<MessageResult> sendMessage(PlayerIdentity identity, String message) {
            synchronized (context) {
                context.requireActive();
                return players.sendMessage(identity, message)
                        .whenCompleteAsync((ignored, failure) -> { }, PLAYER_COMPLETIONS);
            }
        }

        @Override public CompletionStage<DisconnectResult> disconnect(PlayerIdentity identity, String reason) {
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
            private final dev.strataproxy.core.backend.BackendHandle handle;
            private boolean active = true;

            private PluginServerRegistration(dev.strataproxy.core.backend.BackendHandle handle) {
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

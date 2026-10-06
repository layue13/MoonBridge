package dev.moonbridge.core.plugin;

import dev.moonbridge.api.event.Event;
import dev.moonbridge.messaging.Endpoint;
import dev.moonbridge.messaging.internal.LocalMessaging;
import dev.moonbridge.api.InitialPlacementHandler;
import dev.moonbridge.api.PlacementDecision;
import dev.moonbridge.api.PlayerView;
import dev.moonbridge.api.Players;
import dev.moonbridge.api.Plugin;
import dev.moonbridge.api.permission.PermissionProvider;
import net.kyori.adventure.text.Component;
import dev.moonbridge.core.backend.BackendCatalog;
import dev.moonbridge.core.backend.BackendOwner;
import dev.moonbridge.core.control.BackendChannelTransport;
import dev.moonbridge.core.event.EventDispatcher;
import dev.moonbridge.core.permission.PermissionService;
import dev.moonbridge.core.event.TransferPreparation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Owns plugin lifecycles and exposes only the proxy services in the plugin API. */
public final class PluginHost implements AutoCloseable, EventDispatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(PluginHost.class);
    private static final AtomicLong NEXT_HOST_GENERATION = new AtomicLong();

    private final BackendCatalog catalog;
    private final UUID proxyEpoch;
    private final Duration eventTimeout;
    private final PermissionService permissionService;
    private final Duration shutdownTimeout;
    private final ThreadPoolExecutor callbacks;
    private final ThreadPoolExecutor commandWorkers;
    private final ThreadPoolExecutor backendWorkers;
    private final ScheduledThreadPoolExecutor timer;
    private final HostLifecycle lifecycle = new HostLifecycle();
    private final LocalMessaging localMessaging;
    private final EventRouter events;
    private final CommandService commandService;
    private final PlacementService placement;
    private final HostServices services;
    private final List<URLClassLoader> classLoaders = new ArrayList<>();
    private final List<LoadedPlugin> plugins = new ArrayList<>();
    private volatile BackendChannelTransport backendChannelTransport;

    public PluginHost(BackendCatalog catalog, Players players, Duration placementTimeout) {
        this(catalog, players, placementTimeout, Duration.ofSeconds(5));
    }

    public PluginHost(BackendCatalog catalog, Players players, Duration placementTimeout, Duration eventTimeout) {
        this(catalog, players, placementTimeout, eventTimeout,
                Math.max(2, Runtime.getRuntime().availableProcessors()), 128, Duration.ofSeconds(10),
                Math.max(2, Runtime.getRuntime().availableProcessors()), 128, 1024, UUID.randomUUID());
    }

    /** Uses the same process epoch as the proxy session listener. */
    public PluginHost(BackendCatalog catalog, Players players, Duration placementTimeout, Duration eventTimeout,
                      UUID proxyEpoch) {
        this(catalog, players, placementTimeout, eventTimeout,
                Math.max(2, Runtime.getRuntime().availableProcessors()), 128, Duration.ofSeconds(10),
                Math.max(2, Runtime.getRuntime().availableProcessors()), 128, 1024, proxyEpoch);
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
                Duration.ofSeconds(10), accessThreads, accessQueueCapacity, maxPendingAccess, UUID.randomUUID());
    }

    private PluginHost(BackendCatalog catalog, Players players, Duration placementTimeout, Duration eventTimeout,
                       int callbackThreads, int callbackQueueCapacity, Duration shutdownTimeout,
                       int accessThreads, int accessQueueCapacity, int maxPendingAccess) {
        this(catalog, players, placementTimeout, eventTimeout, callbackThreads, callbackQueueCapacity,
                shutdownTimeout, accessThreads, accessQueueCapacity, maxPendingAccess, UUID.randomUUID());
    }

    private PluginHost(BackendCatalog catalog, Players players, Duration placementTimeout, Duration eventTimeout,
                       int callbackThreads, int callbackQueueCapacity, Duration shutdownTimeout,
                       int accessThreads, int accessQueueCapacity, int maxPendingAccess, UUID proxyEpoch) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(players, "players");
        this.proxyEpoch = Objects.requireNonNull(proxyEpoch, "proxyEpoch");
        Objects.requireNonNull(placementTimeout, "placementTimeout");
        Objects.requireNonNull(eventTimeout, "eventTimeout");
        this.eventTimeout = eventTimeout;
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
        this.callbacks = boundedPool(callbackThreads, callbackQueueCapacity, "moonbridge-plugin-callback");
        this.commandWorkers = boundedPool(callbackThreads, callbackQueueCapacity, "moonbridge-plugin-command");
        this.backendWorkers = boundedPool(2, 128, "moonbridge-plugin-backend");
        this.timer = new ScheduledThreadPoolExecutor(1, PluginThreads.named("moonbridge-plugin-timeout"));
        this.timer.setRemoveOnCancelPolicy(true);
        this.events = new EventRouter(lifecycle, this, eventTimeout, timer, accessThreads, accessQueueCapacity,
                maxPendingAccess);
        this.localMessaging = new LocalMessaging(Endpoint.proxy(),
                new HostMessagingOutbound(this::localMessaging, () -> backendChannelTransport), backendWorkers, timer);
        this.commandService = new CommandService(lifecycle, this, players, permissionService, commandWorkers, timer);
        this.placement = new PlacementService(lifecycle, this, catalog, placementTimeout, callbacks, timer);
        this.services = new HostServices(catalog, players, proxyEpoch, permissionService, localMessaging,
                commandService, events, lifecycle);
    }

    private static ThreadPoolExecutor boundedPool(int threads, int queueCapacity, String name) {
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), PluginThreads.named(name),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** Loads every JAR in the directory in filename order through Java's service provider mechanism. */
    public synchronized void loadPlugins(Path directory) throws IOException {
        loadPlugins(directory, null);
    }

    /** Loads only configured provider classes; an empty map enables no external plugins. */
    public synchronized void loadPlugins(Path directory, Map<String, Map<String, String>> enabled) throws IOException {
        lifecycle.require(HostLifecycle.State.LOADING);
        Objects.requireNonNull(directory, "directory");
        if (enabled != null && enabled.isEmpty()) return;
        if (!Files.exists(directory)) {
            if (enabled != null) throw new IOException("Configured plugin directory does not exist: " + directory);
            return;
        }
        if (!Files.isDirectory(directory)) {
            throw new IOException("Plugin path is not a directory: " + directory);
        }
        List<Path> jars = PluginJars.list(directory);
        var remaining = enabled == null ? null : new HashSet<>(enabled.keySet());
        for (Path jar : jars) {
            try {
                List<String> selected = enabled == null ? null : PluginJars.declaredProviders(jar).stream()
                        .filter(enabled::containsKey).toList();
                if (selected != null && selected.isEmpty()) continue;
                PluginClassLoader loader = new PluginClassLoader(new java.net.URL[]{jar.toUri().toURL()},
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

    /** Loads plugin instances directly, primarily for embedding and tests. */
    public synchronized void load(List<? extends Plugin> instances) {
        lifecycle.require(HostLifecycle.State.LOADING);
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
        lifecycle.require(HostLifecycle.State.LOADING);
        try {
            for (LoadedPlugin loaded : plugins) {
                Optional<InitialPlacementHandler> candidate = Objects.requireNonNull(
                        loaded.plugin.initialPlacementHandler(), "initialPlacementHandler result");
                if (candidate.isPresent()) placement.install(candidate.get(), loaded.owner.id());
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
            selectPermissionProvider();
            events.freeze();
            lifecycle.set(HostLifecycle.State.ENABLED);
            events.startReporting();
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    private void selectPermissionProvider() {
        PermissionProvider selected = null;
        String providerOwner = null;
        for (LoadedPlugin loaded : plugins) {
            Optional<PermissionProvider> candidate = Objects.requireNonNull(
                    loaded.plugin.permissionProvider(), "permissionProvider result");
            if (candidate.isPresent()) {
                if (selected != null) {
                    throw new IllegalStateException("Only one plugin may provide player permissions; conflict at "
                            + loaded.owner.id() + " (already provided by " + providerOwner + ")");
                }
                selected = candidate.get();
                providerOwner = loaded.owner.id();
            }
        }
        if (selected != null) permissionService.configure(selected);
    }

    @Override public boolean hasSubscribers(Class<?> eventType) { return events.hasSubscribers(eventType); }

    @Override public <R> CompletionStage<R> dispatch(Event<R> event) { return events.dispatch(event); }

    @Override public TransferPreparation selectTransferPreparation() { return events.selectTransferPreparation(); }

    /** Installs the control service used by plugin backend-channel operations. */
    public synchronized void setBackendChannelTransport(BackendChannelTransport transport) {
        if (lifecycle.closed()) throw new IllegalStateException("Plugin host is closed");
        backendChannelTransport = Objects.requireNonNull(transport, "transport");
    }

    /** Host-side dispatcher used by the authenticated control transport. */
    public LocalMessaging localMessaging() { return localMessaging; }

    /** Player permission lifecycle and query service used by the connection/session layer. */
    public PermissionService permissionService() { return permissionService; }

    /** Returns empty when no plugin handles placement; plugin code always runs off the caller's event loop. */
    public CompletionStage<Optional<PlacementDecision>> placeInitial(PlayerView player) {
        return placement.placeInitial(player);
    }

    /** Returns up to 100 registered roots matching a case-insensitive prefix without a leading slash. */
    public List<String> commandNames(String prefix) { return commandService.commandNames(prefix); }

    /** Returns roots visible to this player; unavailable, undefined, and denied nodes stay hidden. */
    public List<String> commandNames(PlayerView player, String prefix) {
        return commandService.commandNames(player, prefix);
    }

    /** Filters a root suggestion without hiding suggestions owned only by a backend. */
    public boolean commandVisible(PlayerView player, String suggestion) {
        return commandService.commandVisible(player, suggestion);
    }

    /**
     * Completes arguments for a known slash command. Root-only input belongs to {@link #commandNames(String)};
     * the callback receives arguments after exactly one root separator, preserving all remaining whitespace.
     */
    public Optional<CompletionStage<List<String>>> completeCommand(PlayerView player, String input) {
        return commandService.completeCommand(player, input);
    }

    /** Claims only known root commands; caller retains and forwards every other chat frame. */
    public boolean dispatchCommand(PlayerView player, String message, Consumer<Component> reply) {
        return commandService.dispatchCommand(player, message, reply, () -> true);
    }

    /** Admission is checked only after the command name is known, preserving unknown-command passthrough. */
    public boolean dispatchCommand(PlayerView player, String message, Consumer<Component> reply,
                                   BooleanSupplier admission) {
        return commandService.dispatchCommand(player, message, reply, admission);
    }

    /** Dispatches a command from the trusted local console, with no synthetic player identity. */
    public boolean dispatchConsoleCommand(String input, Consumer<Component> reply) {
        return commandService.dispatchConsoleCommand(input, reply);
    }

    @Override
    public synchronized void close() {
        if (lifecycle.closed()) return;
        lifecycle.set(HostLifecycle.State.CLOSED);
        permissionService.close();
        commandService.failPendingExecutions();
        events.closeDispatchers();
        localMessaging.close();
        events.stopReporting();
        for (LoadedPlugin loaded : plugins) loaded.context.revokeEventSubscriptions();
        placement.closeAll();
        commandService.failPendingCompletions();
        callbacks.shutdownNow();
        commandWorkers.shutdownNow();
        backendWorkers.shutdownNow();
        timer.shutdownNow();
        long deadline = System.nanoTime() + shutdownTimeout.toNanos();
        for (int index = plugins.size() - 1; index >= 0; index--) {
            LoadedPlugin loaded = plugins.get(index);
            if (loaded.enabled) disableWithinDeadline(loaded, deadline);
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
        placement.clear();
        events.clear();
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
                         PluginClassLoader pluginClassLoader) {
        long generation = NEXT_HOST_GENERATION.updateAndGet(value -> {
            if (value == Long.MAX_VALUE) {
                throw new IllegalStateException("Plugin host generation exhausted");
            }
            return value + 1;
        });
        BackendOwner owner = new BackendOwner(id, generation);
        PluginContextImpl context = new PluginContextImpl(services, owner, settings, dataDirectory, pluginClassLoader);
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
}

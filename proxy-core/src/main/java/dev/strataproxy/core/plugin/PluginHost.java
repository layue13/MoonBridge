package dev.strataproxy.core.plugin;

import dev.strataproxy.api.InitialPlacementHandler;
import dev.strataproxy.api.PlacementDecision;
import dev.strataproxy.api.PlayerView;
import dev.strataproxy.api.Players;
import dev.strataproxy.api.Plugin;
import dev.strataproxy.api.PluginContext;
import dev.strataproxy.api.ServerDefinition;
import dev.strataproxy.api.ServerRegistration;
import dev.strataproxy.api.ServerView;
import dev.strataproxy.api.Servers;
import dev.strataproxy.core.backend.BackendCatalog;
import dev.strataproxy.core.backend.BackendId;
import dev.strataproxy.core.backend.BackendOwner;
import dev.strataproxy.core.backend.BackendRegistration;
import dev.strataproxy.core.backend.BackendView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Owns plugin lifecycles and exposes only the proxy services in the plugin API. */
public final class PluginHost implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(PluginHost.class);
    private static final AtomicLong NEXT_HOST_GENERATION = new AtomicLong();

    private final BackendCatalog catalog;
    private final Players players;
    private final Duration placementTimeout;
    private final ThreadPoolExecutor callbacks;
    private final ScheduledExecutorService timer;
    private final List<URLClassLoader> classLoaders = new ArrayList<>();
    private final List<LoadedPlugin> plugins = new ArrayList<>();
    private State state = State.LOADING;
    private InitialPlacementHandler placementHandler;
    private LoadedPlugin placementPlugin;

    public PluginHost(BackendCatalog catalog, Players players, Duration placementTimeout) {
        this(catalog, players, placementTimeout, Math.max(2, Runtime.getRuntime().availableProcessors()), 128);
    }

    PluginHost(BackendCatalog catalog, Players players, Duration placementTimeout,
               int callbackThreads, int callbackQueueCapacity) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.players = Objects.requireNonNull(players, "players");
        this.placementTimeout = Objects.requireNonNull(placementTimeout, "placementTimeout");
        if (placementTimeout.isZero() || placementTimeout.isNegative()) {
            throw new IllegalArgumentException("placementTimeout must be positive");
        }
        if (callbackThreads < 1 || callbackQueueCapacity < 1) {
            throw new IllegalArgumentException("callback executor sizes must be positive");
        }
        this.callbacks = new ThreadPoolExecutor(callbackThreads, callbackThreads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(callbackQueueCapacity), namedThreads("strataproxy-plugin-callback"),
                new ThreadPoolExecutor.AbortPolicy());
        this.timer = Executors.newSingleThreadScheduledExecutor(namedThreads("strataproxy-plugin-timeout"));
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
            URLClassLoader loader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, Plugin.class.getClassLoader());
            classLoaders.add(loader);
            try {
                ServiceLoader.load(Plugin.class, loader).stream().forEach(provider -> {
                    String className = provider.type().getName();
                    if (enabled != null && !enabled.containsKey(className)) return;
                    if (remaining != null && !remaining.remove(className)) {
                        throw new IllegalStateException("Duplicate configured plugin provider: " + className);
                    }
                    Plugin plugin = provider.get();
                    loadOne(plugin, jar.getFileName() + ":" + className,
                            enabled == null ? Map.of() : enabled.get(className));
                });
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

    /** Calls onEnable after all providers have loaded and validates the single placement hook. */
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
                    placementPlugin = loaded;
                }
            }
            for (LoadedPlugin loaded : plugins) {
                loaded.enabled = true;
                loaded.plugin.onEnable();
            }
            state = State.ENABLED;
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    /** Returns empty when no plugin handles placement; plugin code always runs off the caller's event loop. */
    public CompletionStage<Optional<PlacementDecision>> placeInitial(PlayerView player) {
        Objects.requireNonNull(player, "player");
        final InitialPlacementHandler handler;
        final LoadedPlugin handlerPlugin;
        synchronized (this) {
            if (state != State.ENABLED) {
                return CompletableFuture.failedFuture(new IllegalStateException("Plugin host is not enabled"));
            }
            handler = placementHandler;
            handlerPlugin = placementPlugin;
        }
        if (handler == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        CompletableFuture<Optional<PlacementDecision>> result = new CompletableFuture<>();
        var timeoutTask = timer.schedule(
                () -> {
                    PlacementTimeoutException timeout = new PlacementTimeoutException(placementTimeout);
                    failPlacementPlugin(handlerPlugin, timeout);
                    result.completeExceptionally(timeout);
                },
                placementTimeout.toNanos(), TimeUnit.NANOSECONDS);
        result.whenComplete((ignored, failure) -> timeoutTask.cancel(false));
        try {
            callbacks.execute(() -> {
                if (result.isDone()) {
                    return;
                }
                try {
                    CompletionStage<PlacementDecision> stage = Objects.requireNonNull(
                            handler.place(player, snapshotServers()), "placement handler stage");
                    stage.whenComplete((decision, failure) -> {
                        if (failure != null) {
                            failPlacementPlugin(handlerPlugin, failure);
                            result.completeExceptionally(failure);
                        } else if (decision == null) {
                            result.completeExceptionally(new IllegalStateException("Placement handler returned null"));
                        } else {
                            result.complete(Optional.of(decision));
                        }
                    });
                } catch (Throwable failure) {
                    failPlacementPlugin(handlerPlugin, failure);
                    result.completeExceptionally(failure);
                }
            });
        } catch (RejectedExecutionException overloaded) {
            result.completeExceptionally(new PluginOverloadedException(overloaded));
        }
        return result;
    }

    private void failPlacementPlugin(LoadedPlugin loaded, Throwable cause) {
        synchronized (this) {
            if (state == State.CLOSED || !loaded.enabled) {
                return;
            }
            loaded.enabled = false;
            loaded.context.deactivate();
            catalog.removeOwner(loaded.owner);
            if (placementPlugin == loaded) {
                placementPlugin = null;
                placementHandler = null;
            }
        }
        try {
            callbacks.execute(() -> {
                try {
                    loaded.plugin.onDisable();
                } catch (Throwable disableFailure) {
                    LOGGER.warn("Plugin {} failed during onDisable after placement failure",
                            loaded.owner.id(), disableFailure);
                }
            });
        } catch (RejectedExecutionException overloaded) {
            LOGGER.warn("Could not schedule onDisable for failed plugin {}", loaded.owner.id(), cause);
        }
    }

    @Override
    public synchronized void close() {
        if (state == State.CLOSED) {
            return;
        }
        state = State.CLOSED;
        for (int index = plugins.size() - 1; index >= 0; index--) {
            LoadedPlugin loaded = plugins.get(index);
            if (loaded.enabled) {
                try {
                    loaded.plugin.onDisable();
                } catch (Throwable failure) {
                    LOGGER.warn("Plugin {} failed during onDisable", loaded.owner.id(), failure);
                }
            }
            loaded.context.deactivate();
            catalog.removeOwner(loaded.owner);
        }
        callbacks.shutdownNow();
        timer.shutdownNow();
        for (URLClassLoader loader : classLoaders) {
            try {
                loader.close();
            } catch (IOException failure) {
                LOGGER.warn("Could not close plugin class loader", failure);
            }
        }
        classLoaders.clear();
        placementHandler = null;
        placementPlugin = null;
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
        try {
            plugin.onLoad(context);
        } catch (Throwable failure) {
            context.deactivate();
            catalog.removeOwner(owner);
            plugins.remove(loaded);
            throw new PluginLoadException(id, failure);
        }
    }

    private List<ServerView> snapshotServers() {
        return catalog.snapshot().stream().map(PluginHost::toServerView).toList();
    }

    private static ServerView toServerView(BackendView view) {
        return new ServerView(view.handle().id().value(), view.address(), view.tags(), view.metadata(),
                view.capacity(), view.connectedPlayers(), view.reservedCapacity());
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
        private final PluginServers servers;
        private final Logger logger;
        private final Map<String, String> settings;
        private volatile boolean active = true;

        private PluginContextImpl(BackendOwner owner, Map<String, String> settings) {
            this.owner = owner;
            this.servers = new PluginServers(this);
            this.logger = LoggerFactory.getLogger("plugin." + owner.id());
            this.settings = Map.copyOf(settings);
        }

        @Override public Players players() { return players; }
        @Override public Servers servers() { return servers; }
        @Override public Logger logger() { return logger; }
        @Override public Map<String, String> settings() { return settings; }

        private void deactivate() { active = false; }
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
            Objects.requireNonNull(backendName, "backendName");
            return catalog.find(new BackendId(backendName)).map(PluginHost::toServerView);
        }

        @Override
        public List<ServerView> all() {
            return snapshotServers();
        }

        @Override
        public ServerRegistration register(ServerDefinition definition) {
            if (!context.active) {
                throw new IllegalStateException("Plugin context is inactive");
            }
            Objects.requireNonNull(definition, "definition");
            BackendId id = new BackendId(definition.name());
            BackendRegistration registration = new BackendRegistration(id, owner, definition.address(),
                    definition.capacity(), definition.tags(), definition.metadata());
            BackendView view = catalog.register(registration);
            return new PluginServerRegistration(view.handle());
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
                        replacement.capacity(), replacement.tags(), replacement.metadata());
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

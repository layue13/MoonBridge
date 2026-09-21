package dev.strataproxy.app;

import dev.strataproxy.app.registry.RegistryPersistenceService;
import dev.strataproxy.app.registry.JsonRegistryStore;
import dev.strataproxy.app.registry.NoopRegistryStore;
import dev.strataproxy.app.registry.RegistryStore;
import dev.strataproxy.agent.BackendAgentServer;
import dev.strataproxy.bootstrap.ConfigLoader;
import dev.strataproxy.bootstrap.ConfigValidationResult;
import dev.strataproxy.bootstrap.ConfigValidator;
import dev.strataproxy.bootstrap.ProxyConfig;
import dev.strataproxy.command.BuiltInProxyCommands;
import dev.strataproxy.command.DefaultCommandRegistry;
import dev.strataproxy.command.DefaultScheduler;
import dev.strataproxy.command.SimpleEventBus;
import dev.strataproxy.compression.CompressionStrategies;
import dev.strataproxy.network.MinecraftForwardingRuntime;
import dev.strataproxy.network.MinecraftAuthRuntime;
import dev.strataproxy.network.MinecraftStatusRuntime;
import dev.strataproxy.network.NettyProxyNetworkServer;
import dev.strataproxy.network.NetworkTuning;
import dev.strataproxy.network.RoutingBackendResolver;
import dev.strataproxy.nativefeature.NativeCapabilityDetector;
import dev.strataproxy.nativefeature.NativeFeature;
import dev.strataproxy.nativefeature.NativeRuntimeDecision;
import dev.strataproxy.nativefeature.NativeRuntimeOptions;
import dev.strataproxy.observability.ProxyMetrics;
import dev.strataproxy.plugin.event.ProxyStartedEvent;
import dev.strataproxy.plugin.event.ProxyStoppingEvent;
import dev.strataproxy.plugin.loader.PluginManager;
import dev.strataproxy.plugin.service.PlayerTransfer;
import dev.strataproxy.plugin.service.PlayerView;
import dev.strataproxy.plugin.service.Scheduler;
import dev.strataproxy.plugin.service.ServerMutationResult;
import dev.strataproxy.plugin.service.ServerPersistence;
import dev.strataproxy.plugin.service.ServerRegistration;
import dev.strataproxy.plugin.service.ServerRemoval;
import dev.strataproxy.plugin.service.ServerView;
import dev.strataproxy.registry.InMemoryServerRegistry;
import dev.strataproxy.registry.TcpServerHealthChecker;
import dev.strataproxy.routing.WeightedHealthAwareRouter;
import dev.strataproxy.api.server.DrainPolicy;
import dev.strataproxy.api.server.ProtocolRange;
import dev.strataproxy.api.server.RegisteredServer;
import dev.strataproxy.api.server.ServerCapability;
import dev.strataproxy.api.server.ServerDescriptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.LoggerFactory;

/**
 * Main application entry point that wires configuration, registry, network, plugins, and shutdown.
 */
public final class StrataProxyLauncher {
    private static final String VERSION = "0.2.0-SNAPSHOT";

    private StrataProxyLauncher() {
    }

    /**
     * Starts StrataProxy from command-line arguments.
     *
     * @param args launcher arguments
     * @throws Exception when startup fails before the launcher can report a structured exit code
     */
    public static void main(String[] args) throws Exception {
        var code = run(args);
        if (LauncherArguments.parse(args).terminalMode() || code != 0) {
            System.exit(code);
        }
    }

    static int run(String[] args) throws Exception {
        var arguments = LauncherArguments.parse(args);
        if (arguments.error() != null) {
            System.err.println(arguments.error());
            printUsage(System.err);
            return 2;
        }
        if (arguments.help()) {
            printUsage(System.out);
            return 0;
        }
        if (arguments.version()) {
            System.out.println("StrataProxy " + VERSION);
            return 0;
        }
        var configPath = ConfigPathResolver.resolve(arguments.configArgs());
        if ((arguments.validateConfig() || arguments.explicitConfig()) && Files.notExists(configPath)) {
            System.err.println("StrataProxy config missing: " + configPath.toAbsolutePath());
            return 1;
        }
        ConfigLoader.LoadedProxyConfig loaded;
        ConfigValidationResult validation;
        try {
            loaded = new ConfigLoader().load(configPath);
            loaded = resolveConfigRelativePaths(configPath, loaded);
            validation = new ConfigValidator().validate(loaded);
        } catch (Exception exception) {
            if (arguments.validateConfig()) {
                System.out.println("ERROR failed to parse config: " + exception.getMessage());
                return 1;
            }
            System.err.println("StrataProxy config error: " + exception.getMessage());
            return 1;
        }
        if (arguments.validateConfig()) {
            for (var warning : validation.warnings()) {
                System.out.println("WARN " + warning);
            }
            for (var error : validation.errors()) {
                System.out.println("ERROR " + error);
            }
            if (validation.valid()) {
                System.out.println("StrataProxy config OK: " + configPath.toAbsolutePath());
                System.out.println("StrataProxy configured servers: " + loaded.servers().size());
                return 0;
            }
            return 1;
        }
        if (!validation.valid()) {
            System.err.println("StrataProxy config invalid: " + String.join("; ", validation.errors()));
            return 1;
        }
        try {
            try (var runtime = startRuntime(loaded, configPath, System.out, true)) {
                runtime.awaitShutdown();
            }
            return 0;
        } catch (Exception exception) {
            System.err.println("StrataProxy startup failed: " + rootMessage(exception));
            return 1;
        }
    }

    static RunningProxy startRuntime(
            ConfigLoader.LoadedProxyConfig loaded,
            Path configPath,
            java.io.PrintStream out,
            boolean registerShutdownHooks) throws Exception {
        loaded = resolveConfigRelativePaths(configPath, loaded);
        var config = loaded.proxy();
        var started = new ArrayList<AutoCloseable>();
        try {
            var registry = new InMemoryServerRegistry();
            loaded.servers().forEach(registry::register);
            var registryPersistencePath = config.registry().persistenceEnabled()
                    ? Path.of(config.registry().persistencePath())
                    : null;
            RegistryStore registryStore = config.registry().persistenceEnabled()
                    ? new JsonRegistryStore(registryPersistencePath)
                    : NoopRegistryStore.INSTANCE;
            for (var descriptor : loadPersistedRegistry(registryStore, registryPersistencePath, out)) {
                if (registry.get(descriptor.name()).isEmpty()) {
                    registry.register(descriptor);
                }
            }
            var registryPersistence = new RegistryPersistenceService(registry, registryStore);
            registryPersistence.persist();

            var router = new WeightedHealthAwareRouter(registry);
            var resolver = new RoutingBackendResolver(router, registry);
            var metrics = new ProxyMetrics();
            var commandRegistry = new DefaultCommandRegistry();
            var eventBus = new SimpleEventBus();
            var scheduler = new DefaultScheduler();
            started.add(scheduler);
            var serverReference = new AtomicReference<NettyProxyNetworkServer>();
            var pluginPlayers = new PluginPlayerService(serverReference, metrics);
            var pluginServers = new PluginServerService(registry, registryPersistence, scheduler, "strataproxy");
            BuiltInProxyCommands.register(commandRegistry, pluginPlayers, pluginServers);
            var pluginManager = new PluginManager(
                    commandRegistry,
                    eventBus,
                    pluginPlayers,
                    metadata -> new PluginServerService(registry, registryPersistence, scheduler, metadata.id()),
                    scheduler,
                    LoggerFactory.getLogger("dev.strataproxy.plugin"));
            started.add(pluginManager);
            var pluginDirectory = resolveConfigRelativePath(configPath, "plugins");
            var loadedPlugins = pluginManager.loadDirectory(pluginDirectory);
            out.println("StrataProxy plugins: directory=" + pluginDirectory + " loaded=" + loadedPlugins.size());
            started.add(() -> eventBus.publish(new ProxyStoppingEvent()));
            var nativeDecision = NativeRuntimeDecision.resolve(nativeOptions(config.nativeRuntime()), NativeCapabilityDetector.detect());
            metrics.nativeRuntime(
                    nativeDecision.enabled(),
                    nativeDecision.capabilities().os(),
                    nativeDecision.capabilities().arch(),
                    nativeDecision.capabilities().detectionSource(),
                    nativeDecision.tlsProvider(),
                    nativeDecision.compressionProvider(),
                    nativeDecision.preferNativeTransport(),
                    nativeDecision.requireNativeTransport(),
                    nativeDecision.capabilities().featureMap(nativeDecision.enabledFeatures()));
            var compressionStrategy = CompressionStrategies.from(config.compression().mode());
            out.println("StrataProxy config: " + configPath.toAbsolutePath());
            out.println("StrataProxy servers: " + registry.snapshot().size());
            out.println("StrataProxy compression: " + config.compression().mode()
                    + " strategy=" + compressionStrategy.getClass().getSimpleName()
                    + " thresholds=" + config.compression().minThreshold() + ".." + config.compression().maxThreshold()
                    + " cpuGuard=" + config.compression().cpuGuard()
                    + " rewriteEnabled=" + config.compression().rewriteEnabled()
                    + " rewriteMaxEventLoopDelayMillis=" + config.compression().rewriteMaxEventLoopDelayMillis());
            out.println("StrataProxy auth: onlineMode=" + config.auth().onlineMode()
                    + " sessionVerification=" + config.auth().sessionVerification()
                    + " rsaKeyBits=" + config.auth().rsaKeyBits()
                    + " sessionVerificationTimeout=" + config.auth().sessionVerificationTimeout());
            out.println("StrataProxy forwarding: mode=" + config.forwarding().mode());
            out.println("StrataProxy status: enabled=" + config.status().enabled()
                    + " protocol=" + config.status().protocolName() + "/" + config.status().protocolVersion()
                    + " maxPlayers=" + config.status().maxPlayers());
            out.println("StrataProxy native runtime: enabled=" + nativeDecision.enabled()
                    + " os=" + nativeDecision.capabilities().os()
                    + " arch=" + nativeDecision.capabilities().arch()
                    + " source=" + nativeDecision.capabilities().detectionSource()
                    + " tlsProvider=" + nativeDecision.tlsProvider()
                    + " compressionProvider=" + nativeDecision.compressionProvider()
                    + " preferNativeTransport=" + nativeDecision.preferNativeTransport()
                    + " requireNativeTransport=" + nativeDecision.requireNativeTransport()
                    + " features=" + nativeDecision.enabledFeatures().stream().map(NativeFeature::label).sorted().toList());
            out.println("StrataProxy starting on " + config.bindAddress() + " with " + config.resolvedWorkerThreads()
                    + " worker threads; proxyProtocol=" + config.network().proxyProtocol()
                    + " maxNewConnectionsPerSecond=" + config.network().maxNewConnectionsPerSecond()
                    + " maxNewConnectionsPerAddressPerSecond=" + config.network().maxNewConnectionsPerAddressPerSecond());

            if (config.registry().healthCheckEnabled()) {
                var healthChecker = new TcpServerHealthChecker(
                        registry,
                        config.registry().healthCheckInterval(),
                        config.registry().healthCheckTimeout(),
                        config.registry().healthCheckMode());
                healthChecker.start();
                started.add(healthChecker);
                out.println("StrataProxy health checks every " + config.registry().healthCheckInterval()
                        + " using " + config.registry().healthCheckMode());
            }

            var loadReporter = new ProxyObservedLoadReporter(registry, metrics, config.observability().flushInterval());
            loadReporter.start();
            started.add(loadReporter);
            out.println("StrataProxy observed load flush interval " + config.observability().flushInterval());

            if (config.backendAgent().enabled()) {
                var backendAgent = new BackendAgentServer(
                        config.backendAgent(),
                        new PluginServerService(registry, registryPersistence, scheduler, "bukkit-agent"));
                backendAgent.start();
                started.add(backendAgent);
                out.println("StrataProxy backend-agent bound on " + config.backendAgent().bindAddress()
                        + " heartbeatTimeout=" + config.backendAgent().heartbeatTimeout());
            }

            var server = new NettyProxyNetworkServer(
                    config.resolvedWorkerThreads(),
                    resolver,
                    metrics,
                    new NetworkTuning(
                            config.network().maxFrameBytes(),
                            config.network().connectTimeoutMillis(),
                            config.network().writeBufferLowBytes(),
                            config.network().writeBufferHighBytes(),
                            config.network().maxConnections(),
                            config.network().maxConnectionsPerAddress(),
                            config.network().maxNewConnectionsPerSecond(),
                            config.network().maxNewConnectionsPerAddressPerSecond(),
                            config.network().initialHandshakeTimeoutMillis(),
                            config.network().proxyProtocol()),
                    config.nativeTransport() && nativeDecision.preferNativeTransport(),
                    compressionStrategy,
                    config.compression().minThreshold(),
                    config.compression().maxThreshold(),
                    config.compression().cpuGuard(),
                    null,
                    authRuntime(config.auth()),
                    forwardingRuntime(config.forwarding()),
                    statusRuntime(config.status(), metrics),
                    config.compression().rewriteEnabled(),
                    config.compression().rewriteMaxEventLoopDelayMillis(),
                    commandRegistry,
                    eventBus);
            serverReference.set(server);
            started.add(server);
            if (nativeDecision.requireNativeTransport() && !server.nativeTransport()) {
                throw new IllegalStateException("native transport is required but unavailable; selected transport=" + server.transportName());
            }
            server.bind(config.bindAddress()).toCompletableFuture().join();
            out.println("StrataProxy bound on " + server.bindAddress() + " using " + server.transportName() + " transport");

            eventBus.publish(new ProxyStartedEvent());
            var running = new RunningProxy(server, List.copyOf(started), metrics);
            if (registerShutdownHooks) {
                Runtime.getRuntime().addShutdownHook(new Thread(running::close, "strataproxy-shutdown"));
            }
            return running;
        } catch (Exception exception) {
            closeStarted(started);
            throw exception;
        }
    }

    private static void closeStarted(List<AutoCloseable> started) {
        var reverse = new ArrayList<>(started);
        Collections.reverse(reverse);
        for (var resource : reverse) {
            try {
                resource.close();
            } catch (Exception ignored) {
                // Startup failure cleanup is best effort; the original startup error is more useful.
            }
        }
    }

    private static List<dev.strataproxy.api.server.ServerDescriptor> loadPersistedRegistry(
            RegistryStore registryStore,
            Path registryPersistencePath,
            java.io.PrintStream out) throws Exception {
        try {
            return registryStore.load();
        } catch (Exception exception) {
            var quarantined = quarantineRegistry(registryPersistencePath);
            out.println("WARN failed to load persisted registry"
                    + (quarantined == null ? "" : "; moved bad file to " + quarantined)
                    + ": " + rootMessage(exception));
            return List.of();
        }
    }

    private static Path quarantineRegistry(Path path) {
        if (path == null || Files.notExists(path)) {
            return null;
        }
        var timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(':', '-');
        var target = path.resolveSibling(path.getFileName() + ".invalid-" + timestamp);
        try {
            Files.move(path, target);
            return target;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String rootMessage(Throwable throwable) {
        var current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        var message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static Path resolveConfigRelativePath(Path configPath, String configuredPath) {
        var path = Path.of(configuredPath);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        var parent = configPath.toAbsolutePath().normalize().getParent();
        return (parent == null ? path.toAbsolutePath() : parent.resolve(path)).normalize();
    }

    private static ConfigLoader.LoadedProxyConfig resolveConfigRelativePaths(
            Path configPath,
            ConfigLoader.LoadedProxyConfig loaded) {
        var config = loaded.proxy();
        var registry = config.registry();
        var resolvedRegistry = new ProxyConfig.RegistryConfig(
                registry.staticServers(),
                registry.persistenceEnabled(),
                registry.persistencePath().isBlank()
                        ? registry.persistencePath()
                        : resolveConfigRelativePath(configPath, registry.persistencePath()).toString(),
                registry.healthCheckEnabled(),
                registry.healthCheckInterval(),
                registry.healthCheckTimeout(),
                registry.healthCheckMode());

        var resolvedConfig = new ProxyConfig(
                config.bindAddress(),
                config.workerThreads(),
                config.nativeTransport(),
                config.network(),
                resolvedRegistry,
                config.compression(),
                config.observability(),
                config.status(),
                config.auth(),
                config.forwarding(),
                config.nativeRuntime());
        return new ConfigLoader.LoadedProxyConfig(resolvedConfig, loaded.servers());
    }

    private static NativeRuntimeOptions nativeOptions(ProxyConfig.NativeConfig config) {
        var nativeConfig = config == null ? ProxyConfig.NativeConfig.defaults() : config;
        return new NativeRuntimeOptions(
                nativeConfig.enabled(),
                nativeConfig.autoDetect(),
                nativeConfig.preferNativeTransport(),
                nativeConfig.requireNativeTransport(),
                nativeConfig.preferOpenSslTls(),
                nativeConfig.preferNativeCompression(),
                nativeFeatures(nativeConfig.disabledFeatures()),
                nativeFeatures(nativeConfig.forcedFeatures()));
    }

    private static MinecraftAuthRuntime authRuntime(ProxyConfig.AuthConfig config) {
        var auth = config == null ? ProxyConfig.AuthConfig.defaults() : config;
        if (!auth.onlineMode()) {
            return MinecraftAuthRuntime.offline();
        }
        return MinecraftAuthRuntime.online(
                auth.rsaKeyBits(),
                auth.verifyTokenBytes(),
                auth.sessionVerification(),
                auth.sessionVerificationTimeout());
    }

    private static MinecraftForwardingRuntime forwardingRuntime(ProxyConfig.ForwardingConfig config) {
        var forwarding = config == null ? ProxyConfig.ForwardingConfig.defaults() : config;
        return new MinecraftForwardingRuntime(forwarding.mode(), forwarding.secret());
    }

    private static MinecraftStatusRuntime statusRuntime(ProxyConfig.StatusConfig config, ProxyMetrics metrics) {
        var status = config == null ? ProxyConfig.StatusConfig.defaults() : config;
        return new MinecraftStatusRuntime(
                status.enabled(),
                status.motd(),
                status.protocolName(),
                status.protocolVersion(),
                status.maxPlayers(),
                status.favicon(),
                status.samplePlayers().stream()
                        .map(player -> new MinecraftStatusRuntime.SamplePlayer(player.name(), player.id()))
                        .toList(),
                () -> metrics.snapshot().playerSessions().size());
    }

    private static final class PluginPlayerService implements dev.strataproxy.plugin.service.PlayerService {
        private final AtomicReference<NettyProxyNetworkServer> server;
        private final ProxyMetrics metrics;

        private PluginPlayerService(AtomicReference<NettyProxyNetworkServer> server, ProxyMetrics metrics) {
            this.server = server;
            this.metrics = metrics;
        }

        @Override
        /** Provides transfer. */
        public java.util.concurrent.CompletionStage<PlayerTransfer> transfer(String playerName, String targetServer) {
            var current = server.get();
            if (current == null) {
                return CompletableFuture.completedFuture(new PlayerTransfer(false, "server_not_started", playerName, "", targetServer));
            }
            return current.transferPlayer(playerName, targetServer)
                    .thenApply(result -> new PlayerTransfer(
                            result.success(),
                            result.outcome(),
                            new dev.strataproxy.plugin.service.PlayerIdentity(null, ""),
                            result.player(),
                            result.sourceServer(),
                            result.targetServer(),
                            result.success() ? PlayerTransfer.TransferStage.NETWORK_READY : PlayerTransfer.TransferStage.REJECTED,
                            !result.success(),
                            result.success() ? result.targetServer() : result.sourceServer()));
        }

        @Override
        public java.util.concurrent.CompletionStage<dev.strataproxy.plugin.service.PluginMessageResult> sendPluginMessage(
                String playerName, String channel, byte[] payload) {
            var current = server.get();
            return current == null
                    ? CompletableFuture.completedFuture(dev.strataproxy.plugin.service.PluginMessageResult.failure("server_not_started"))
                    : current.sendPluginMessage(playerName, channel, payload);
        }

        @Override
        /** Provides find. */
        public Optional<PlayerView> find(String playerName) {
            if (playerName == null || playerName.isBlank()) {
                return Optional.empty();
            }
            var session = metrics.snapshot().playerSessions().get(playerName.trim());
            return session == null
                    ? Optional.empty()
                    : Optional.of(new PlayerView(new dev.strataproxy.plugin.service.PlayerIdentity(session.playerId(), session.connectionId()), session.player(), session.server(), session.remoteAddress()));
        }

        @Override
        /** Provides online players. */
        public Collection<PlayerView> onlinePlayers() {
            return metrics.snapshot().playerSessions().values().stream()
                    .map(session -> new PlayerView(new dev.strataproxy.plugin.service.PlayerIdentity(session.playerId(), session.connectionId()), session.player(), session.server(), session.remoteAddress()))
                    .toList();
        }
    }

    private static final class PluginServerService implements dev.strataproxy.plugin.service.ServerService {
        private static final String SOURCE_KEY = "strataproxy.source";
        private static final String SOURCE_PLUGIN = "plugin";
        private static final String PLUGIN_ID_KEY = "strataproxy.pluginId";
        private static final String PERSISTENCE_KEY = "strataproxy.persistence";

        private final dev.strataproxy.api.server.ServerRegistry registry;
        private final RegistryPersistenceService registryPersistence;
        private final Scheduler scheduler;
        private final String pluginId;

        private PluginServerService(
                dev.strataproxy.api.server.ServerRegistry registry,
                RegistryPersistenceService registryPersistence,
                Scheduler scheduler,
                String pluginId) {
            this.registry = registry;
            this.registryPersistence = registryPersistence;
            this.scheduler = scheduler;
            this.pluginId = pluginId == null || pluginId.isBlank() ? "unknown" : pluginId.trim();
        }

        @Override
        /** Provides find. */
        public Optional<ServerView> find(String serverName) {
            if (serverName == null || serverName.isBlank()) {
                return Optional.empty();
            }
            return registry.get(serverName.trim()).map(PluginServerService::view);
        }

        @Override
        /** Provides first with tag. */
        public Optional<ServerView> firstWithTag(String tag) {
            if (tag == null || tag.isBlank()) {
                return Optional.empty();
            }
            return registry.snapshot().stream()
                    .map(PluginServerService::view)
                    .filter(server -> server.tags().stream().anyMatch(value -> value.equalsIgnoreCase(tag)))
                    .findFirst();
        }

        @Override
        /** Provides servers. */
        public Collection<ServerView> servers() {
            return registry.snapshot().stream().map(PluginServerService::view).toList();
        }

        @Override
        /** Provides register. */
        public java.util.concurrent.CompletionStage<ServerMutationResult> register(ServerRegistration registration) {
            return mutate(() -> {
                if (registration == null) {
                    return ServerMutationResult.failure("invalid_request", "registration must not be null");
                }
                var ownershipFailure = ownershipFailure(registration.name());
                if (ownershipFailure != null) {
                    return ownershipFailure;
                }
                var descriptor = descriptor(registration);
                var server = registration.persistence() == ServerPersistence.PERSISTENT
                        ? registryPersistence.register(descriptor)
                        : registry.registerOrReplace(descriptor);
                return ServerMutationResult.success("registered", view(server));
            });
        }

        @Override
        /** Provides unregister. */
        public java.util.concurrent.CompletionStage<ServerMutationResult> unregister(String serverName, ServerRemoval removal) {
            return mutate(() -> {
                var name = normalizeName(serverName);
                var existing = registry.get(name);
                if (existing.isEmpty()) {
                    return ServerMutationResult.failure("not_found", "server is not registered: " + name);
                }
                var ownershipFailure = ownershipFailure(name);
                if (ownershipFailure != null) {
                    return ownershipFailure;
                }
                var request = removal == null ? ServerRemoval.ephemeral() : removal;
                var policy = new DrainPolicy(
                        request.rejectNewConnections(),
                        request.migrateExistingPlayers(),
                        request.gracePeriod());
                var removed = request.persistence() == ServerPersistence.PERSISTENT
                        ? registryPersistence.unregister(name, policy)
                        : registry.unregister(name, policy);
                return removed
                        ? ServerMutationResult.success("unregistered", view(existing.get()))
                        : ServerMutationResult.failure("not_found", "server is not registered: " + name);
            });
        }

        @Override
        /** Updates drain mode. */
        public java.util.concurrent.CompletionStage<ServerMutationResult> setDrainMode(
                String serverName,
                boolean drainMode,
                ServerPersistence persistence) {
            return mutate(() -> {
                var name = normalizeName(serverName);
                if (registry.get(name).isEmpty()) {
                    return ServerMutationResult.failure("not_found", "server is not registered: " + name);
                }
                var ownershipFailure = ownershipFailure(name);
                if (ownershipFailure != null) {
                    return ownershipFailure;
                }
                if (persistence == ServerPersistence.PERSISTENT) {
                    var changed = registryPersistence.updateDrainMode(name, drainMode);
                    if (!changed) {
                        return ServerMutationResult.failure("not_found", "server is not registered: " + name);
                    }
                } else {
                    registry.updateDrainMode(name, drainMode);
                }
                return registry.get(name)
                        .map(server -> ServerMutationResult.success(drainMode ? "drained" : "undrained", view(server)))
                        .orElseGet(() -> ServerMutationResult.failure("not_found", "server is not registered: " + name));
            });
        }

        private java.util.concurrent.CompletionStage<ServerMutationResult> mutate(Mutation mutation) {
            var result = new CompletableFuture<ServerMutationResult>();
            try {
                scheduler.runAsync(() -> {
                    try {
                        result.complete(mutation.run());
                    } catch (IllegalArgumentException exception) {
                        result.complete(ServerMutationResult.failure("invalid_request", exception.getMessage()));
                    } catch (Exception exception) {
                        result.complete(ServerMutationResult.failure("mutation_failed", rootMessage(exception)));
                    }
                }).whenComplete((ignored, exception) -> {
                    if (exception != null) {
                        result.complete(ServerMutationResult.failure("mutation_failed", rootMessage(exception)));
                    }
                });
            } catch (RuntimeException exception) {
                result.complete(ServerMutationResult.failure("mutation_failed", rootMessage(exception)));
            }
            return result;
        }

        private ServerDescriptor descriptor(ServerRegistration registration) {
            var metadata = new LinkedHashMap<>(registration.metadata());
            metadata.put(SOURCE_KEY, SOURCE_PLUGIN);
            metadata.put(PLUGIN_ID_KEY, pluginId);
            metadata.put(PERSISTENCE_KEY, registration.persistence().name().toLowerCase(Locale.ROOT));
            return new ServerDescriptor(
                    registration.name(),
                    registration.address(),
                    registration.tags(),
                    capabilities(registration.capabilities()),
                    new ProtocolRange(
                            registration.protocolRange().minProtocol(),
                            registration.protocolRange().maxProtocol(),
                            registration.protocolRange().displayName()),
                    registration.weight(),
                    registration.softCapacity(),
                    registration.hardCapacity(),
                    registration.drainMode(),
                    metadata);
        }

        private ServerMutationResult ownershipFailure(String serverName) {
            var name = normalizeName(serverName);
            var existing = registry.get(name);
            if (existing.isEmpty()) {
                return null;
            }
            var metadata = existing.get().descriptor().metadata();
            if (SOURCE_PLUGIN.equals(metadata.get(SOURCE_KEY)) && pluginId.equals(metadata.get(PLUGIN_ID_KEY))) {
                return null;
            }
            if (SOURCE_PLUGIN.equals(metadata.get(SOURCE_KEY))) {
                return ServerMutationResult.failure(
                        "server_owned_by_other_plugin",
                        "server is owned by plugin: " + metadata.getOrDefault(PLUGIN_ID_KEY, ""));
            }
            return ServerMutationResult.failure("server_not_owned", "server is not owned by plugin: " + pluginId);
        }

        private static Set<ServerCapability> capabilities(Set<String> values) {
            if (values == null || values.isEmpty()) {
                return Set.of();
            }
            return values.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> value.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT))
                    .map(ServerCapability::valueOf)
                    .collect(Collectors.toUnmodifiableSet());
        }

        private static String normalizeName(String serverName) {
            if (serverName == null || serverName.isBlank()) {
                throw new IllegalArgumentException("server name must not be blank");
            }
            return serverName.trim();
        }

        private static ServerView view(RegisteredServer server) {
            var descriptor = server.descriptor();
            return new ServerView(
                    descriptor.name(),
                    descriptor.address(),
                    descriptor.tags(),
                    descriptor.drainMode(),
                    descriptor.softCapacity(),
                    descriptor.hardCapacity());
        }

        @FunctionalInterface
        private interface Mutation {
            ServerMutationResult run() throws Exception;
        }
    }

    private static Set<NativeFeature> nativeFeatures(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        return values.stream().map(NativeFeature::fromLabel).collect(Collectors.toUnmodifiableSet());
    }

    private static void printUsage(java.io.PrintStream out) {
        out.println("Usage: strataproxy [--help] [--version] [--validate-config] [config.yml]");
        out.println();
        out.println("Runs the StrataProxy Minecraft proxy.");
        out.println();
        out.println("Options:");
        out.println("  -h, --help             Show this help message and exit.");
        out.println("  -V, --version          Print version information and exit.");
        out.println("      --validate-config  Validate config and exit without opening sockets.");
        out.println();
        out.println("Config lookup without an explicit path:");
        out.println("  1. ./config/strataproxy.yml");
        out.println("  2. $APP_HOME/config/strataproxy.yml");
        out.println("  3. packaged application home config/strataproxy.yml");
        out.println("  4. built-in defaults");
    }

    record RunningProxy(
            NettyProxyNetworkServer server,
            List<AutoCloseable> started,
            ProxyMetrics metrics,
            AtomicBoolean closed,
            CountDownLatch stopped) implements AutoCloseable {
        RunningProxy(NettyProxyNetworkServer server, List<AutoCloseable> started, ProxyMetrics metrics) {
            this(server, started, metrics, new AtomicBoolean(), new CountDownLatch(1));
        }

        java.net.InetSocketAddress bindAddress() {
            return server.bindAddress();
        }

        void awaitShutdown() throws InterruptedException {
            stopped.await();
        }

        @Override
        /** Provides close. */
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                closeStarted(started);
            } finally {
                stopped.countDown();
            }
        }
    }

    private record LauncherArguments(
            boolean validateConfig,
            boolean help,
            boolean version,
            String[] configArgs,
            String error) {
        boolean explicitConfig() {
            return configArgs.length > 0;
        }

        boolean terminalMode() {
            return validateConfig || help || version;
        }

        private static LauncherArguments parse(String[] args) {
            if (args == null || args.length == 0) {
                return new LauncherArguments(false, false, false, new String[0], null);
            }
            var validate = false;
            var help = false;
            var version = false;
            var configArgs = new ArrayList<String>();
            for (var argument : Arrays.asList(args)) {
                switch (argument) {
                    case "--validate-config" -> validate = true;
                    case "-h", "--help" -> help = true;
                    case "-V", "--version" -> version = true;
                    default -> {
                        if (argument.startsWith("-")) {
                            return new LauncherArguments(validate, help, version, new String[0], "Unknown option: " + argument);
                        }
                        configArgs.add(argument);
                    }
                }
            }
            if (configArgs.size() > 1) {
                return new LauncherArguments(validate, help, version, configArgs.toArray(String[]::new), "Only one config path may be provided");
            }
            return new LauncherArguments(validate, help, version, configArgs.toArray(String[]::new), null);
        }
    }
}

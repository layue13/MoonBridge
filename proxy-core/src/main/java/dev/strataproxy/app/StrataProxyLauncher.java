package dev.strataproxy.app;

import dev.strataproxy.registry.RegistryPersistenceService;
import dev.strataproxy.registry.JsonRegistryStore;
import dev.strataproxy.registry.NoopRegistryStore;
import dev.strataproxy.registry.RegistryStore;
import dev.strataproxy.agent.BackendAgentServer;
import dev.strataproxy.agent.BackendMessageBroker;
import dev.strataproxy.app.ConfigLoader;
import dev.strataproxy.app.ConfigValidationResult;
import dev.strataproxy.app.ConfigValidator;
import dev.strataproxy.app.ProxyConfig;
import dev.strataproxy.command.BuiltInProxyCommands;
import dev.strataproxy.command.DefaultCommandRegistry;
import dev.strataproxy.command.DefaultScheduler;
import dev.strataproxy.command.SimpleEventBus;
import dev.strataproxy.compression.CompressionStrategies;
import dev.strataproxy.network.MinecraftForwardingRuntime;
import dev.strataproxy.network.MinecraftAuthRuntime;
import dev.strataproxy.network.MinecraftStatusRuntime;
import dev.strataproxy.network.NettyProxyNetworkServer;
import dev.strataproxy.network.NettyProxyRuntime;
import dev.strataproxy.network.NettyProxyServerConfig;
import dev.strataproxy.network.NetworkTuning;
import dev.strataproxy.network.StagedBackendResolver;
import dev.strataproxy.nativefeature.NativeCapabilityDetector;
import dev.strataproxy.nativefeature.NativeFeature;
import dev.strataproxy.nativefeature.NativeRuntimeDecision;
import dev.strataproxy.nativefeature.NativeRuntimeOptions;
import dev.strataproxy.network.ProxyMetrics;
import dev.strataproxy.plugin.event.ProxyStartedEvent;
import dev.strataproxy.plugin.event.ProxyStoppingEvent;
import dev.strataproxy.plugin.loader.PluginManager;
import dev.strataproxy.plugin.runtime.PluginPlayerService;
import dev.strataproxy.route.StageRouteEngine;
import dev.strataproxy.plugin.service.Scheduler;
import dev.strataproxy.plugin.service.ServerMutationResult;
import dev.strataproxy.plugin.service.ServerPersistence;
import dev.strataproxy.plugin.service.ServerRegistration;
import dev.strataproxy.plugin.service.ServerRemoval;
import dev.strataproxy.plugin.service.ServerView;
import dev.strataproxy.registry.InMemoryServerRegistry;
import dev.strataproxy.registry.TcpServerHealthChecker;
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

            var metrics = new ProxyMetrics();
            var commandRegistry = new DefaultCommandRegistry();
            var eventBus = new SimpleEventBus();
            var scheduler = new DefaultScheduler();
            started.add(scheduler);
            var routeEngine = new StageRouteEngine();
            started.add(routeEngine);
            var serverReference = new AtomicReference<NettyProxyNetworkServer>();
            var messageBroker = new BackendMessageBroker();
            started.add(messageBroker);
            var pluginServers = new PluginServerService(registry, registryPersistence, scheduler, "strataproxy");
            var resolver = new StagedBackendResolver(registry, routeEngine);
            var pluginPlayers = new PluginPlayerService(serverReference, metrics, routeEngine);
            BuiltInProxyCommands.register(commandRegistry, pluginPlayers, pluginServers);
            var pluginManager = new PluginManager(
                    commandRegistry,
                    eventBus,
                    pluginPlayers,
                    metadata -> messageBroker.forPlugin(metadata.id()),
                    metadata -> new PluginServerService(registry, registryPersistence, scheduler, metadata.id()),
                    metadata -> routeEngine.forPlugin(metadata.id()),
                    scheduler,
                    LoggerFactory.getLogger("dev.strataproxy.plugin"));
            started.add(pluginManager);
            var pluginDirectory = resolveConfigRelativePath(configPath, "plugins");
            var loadedPlugins = pluginManager.loadDirectory(pluginDirectory);
            out.println("StrataProxy plugins: directory=" + pluginDirectory + " loaded=" + loadedPlugins.size());
            started.add(() -> eventBus.publish(new ProxyStoppingEvent()));
            var nativeDecision = NativeRuntimeDecision.resolve(nativeOptions(config.nativeRuntime()), NativeCapabilityDetector.detect());
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
                        new PluginServerService(registry, registryPersistence, scheduler, "bukkit-agent"),
                        messageBroker);
                backendAgent.start();
                started.add(backendAgent);
                out.println("StrataProxy backend-agent bound on " + config.backendAgent().bindAddress()
                        + " heartbeatTimeout=" + config.backendAgent().heartbeatTimeout());
            }

            var server = new NettyProxyNetworkServer(new NettyProxyServerConfig(
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
                    new NettyProxyRuntime(
                            compressionStrategy,
                            config.compression().minThreshold(),
                            config.compression().maxThreshold(),
                            config.compression().cpuGuard(),
                            authRuntime(config.auth()),
                            forwardingRuntime(config.forwarding()),
                            statusRuntime(config.status(), metrics),
                            config.compression().rewriteEnabled(),
                            config.compression().rewriteMaxEventLoopDelayMillis(),
                            commandRegistry,
                            eventBus)));
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
                metrics::onlinePlayerCount);
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

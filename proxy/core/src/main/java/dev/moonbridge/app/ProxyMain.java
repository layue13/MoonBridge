package dev.moonbridge.app;

import dev.moonbridge.core.session.channel.NetworkTransport;
import dev.moonbridge.core.backend.InMemoryBackendCatalog;
import dev.moonbridge.core.control.BackendControlService;
import dev.moonbridge.core.auth.MojangSessionVerifier;
import dev.moonbridge.app.console.BuiltinCommands;
import dev.moonbridge.app.console.Console;
import dev.moonbridge.app.console.LiveAdminActions;
import dev.moonbridge.core.admin.AdminView;
import dev.moonbridge.core.log.ConnectionLog;
import dev.moonbridge.core.plugin.PluginHost;
import dev.moonbridge.core.session.ProxySessionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.Executors;

/** Command line entry point for the new proxy runtime. */
public final class ProxyMain {
    static {
        // Leak detection costs per frame on every hand-written forwarding path, so the proxy runs without it;
        // every proxy-core test class runs with PARANOID detection through LeakGate instead. This must run
        // before Netty is loaded. Re-enable per deployment with -Dio.netty.leakDetection.level=simple.
        if (System.getProperty("io.netty.leakDetection.level") == null) {
            System.setProperty("io.netty.leakDetection.level", "disabled");
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ProxyMain.class);

    private ProxyMain() {
    }

    public static void main(String[] arguments) {
        int exitCode = run(arguments);
        if (exitCode != 0) System.exit(exitCode);
    }

    static int run(String[] arguments) {
        try {
            if (arguments.length == 1 && arguments[0].equals("--help")) {
                System.out.println("Usage: moonbridge [--config <file>] | --validate-config <file> | --version");
                return 0;
            }
            if (arguments.length == 1 && arguments[0].equals("--version")) {
                String version = ProxyMain.class.getPackage().getImplementationVersion();
                System.out.println("MoonBridge " + (version == null ? "development" : version));
                return 0;
            }
            if (arguments.length == 2 && arguments[0].equals("--validate-config")) {
                var loader = new ProxyConfigurationLoader();
                Path configPath = Path.of(arguments[1]);
                var configuration = loader.load(configPath);
                loader.loadServerListStatus(configuration, configPath);
                loader.loadWebSocket(configuration, configPath);
                StaticBackends.register(configuration, new InMemoryBackendCatalog());
                System.out.println("Valid core configuration and static backends: " + configuration.listen()
                        + " (enabled plugin settings are checked at startup)");
                return 0;
            }
            Path configPath;
            if (arguments.length == 0) {
                configPath = Path.of("config", "moonbridge.yml");
            } else if (arguments.length == 2 && arguments[0].equals("--config")) {
                configPath = Path.of(arguments[1]);
            } else {
                System.err.println("Invalid arguments. Use --help for usage.");
                return 2;
            }
            runProxy(configPath);
            return 0;
        } catch (Exception failure) {
            LOGGER.error("MoonBridge failed", failure);
            return 1;
        }
    }

    private static void runProxy(Path configPath) throws Exception {
        var started = java.time.Instant.now();
        var loader = new ProxyConfigurationLoader();
        var configuration = loader.load(configPath);
        String implementationVersion = ProxyMain.class.getPackage().getImplementationVersion();
        String version = implementationVersion == null ? "development" : implementationVersion;
        LOGGER.info("Starting MoonBridge {} on Java {} with {} authentication", version, Runtime.version(),
                configuration.authentication());
        UUID proxyEpoch = UUID.randomUUID();
        var serverListStatus = loader.loadServerListStatus(configuration, configPath);
        var webSocket = loader.loadWebSocket(configuration, configPath);
        var catalog = new InMemoryBackendCatalog();
        StaticBackends.register(configuration, catalog);
        Duration placementTimeout = Duration.ofSeconds(configuration.initialRouting().timeoutSeconds());
        Duration eventTimeout = Duration.ofSeconds(configuration.plugins().eventTimeoutSeconds());
        ExecutorService verifierWorkers = configuration.authentication() == ProxyConfiguration.Authentication.ONLINE_BUNGEE
                ? Executors.newFixedThreadPool(2, task -> {
                    Thread thread = new Thread(task, "moonbridge-session-verify");
                    thread.setDaemon(true);
                    return thread;
                }) : null;
        var transport = NetworkTransport.select(
                NetworkTransport.Preference.valueOf(configuration.transport().name()));
        LOGGER.info("Using {} network transport", transport.name());
        var listener = verifierWorkers == null
                ? new ProxySessionListener(configuration.listenAddress(), catalog, null, placementTimeout, transport)
                : new ProxySessionListener(configuration.listenAddress(), catalog,
                        new MojangSessionVerifier(Duration.ofSeconds(5), verifierWorkers), placementTimeout, transport);
        listener.setSessionBinding(proxyEpoch, sessionBindingSecrets(configuration));
        listener.setServerListStatus(serverListStatus);
        listener.setInitialServers(configuration.initialRouting().servers());
        var connectionLog = new ConnectionLog(configuration.logging().playerAddresses());
        listener.setConnectionLog(connectionLog);
        if (webSocket != null) listener.setWebSocket(configuration.websocket().listenAddress(), webSocket);
        try (var plugins = new PluginHost(catalog, listener, placementTimeout, eventTimeout, proxyEpoch);
             var control = configuration.backendChannel() == null ? null
                     : new BackendControlService(configuration.backendChannel(), catalog,
                     plugins.localMessaging(), proxyEpoch)) {
            if (control != null) plugins.setBackendChannelTransport(control);
            listener.setMaxConnections(configuration.maxConnections());
            plugins.loadPlugins(pluginDirectory(configPath, configuration.plugins().directory()),
                    configuration.plugins().enabled());
            plugins.enable();
            if (control != null) control.start();
            listener.setEvents(plugins, eventTimeout);
            listener.setPermissions(plugins.permissionService());
            listener.setPlacement(plugins::placeInitial);
            listener.setCommandDispatcher(plugins::dispatchCommand);
            listener.setPlayerCommandCompletion(plugins::commandNames, plugins::completeCommand);
            listener.setCommandVisibility(plugins::commandVisible);
            var serverChannel = listener.start().toCompletableFuture().join();
            LOGGER.info("Listening on {}", serverChannel.localAddress());
            LOGGER.info("Ready: {} server(s) in the catalog, {} plugin(s) enabled, backend control channel {}",
                    catalog.snapshot().size(), configuration.plugins().enabled().size(),
                    control == null ? "off" : "on " + configuration.backendChannel().listen());
            var listeners = new java.util.ArrayList<String>();
            listeners.add("TCP " + serverChannel.localAddress());
            if (webSocket != null) {
                // OPENSSL is the bundled BoringSSL; JDK means it could not load on this platform.
                LOGGER.info("Listening for WebSocket{} on {}{}", webSocket.tls() == null ? ""
                                : " (TLS via " + (webSocket.tls()
                                        instanceof io.netty.handler.ssl.ReferenceCountedOpenSslContext
                                        ? "OPENSSL" : "JDK") + ")",
                        listener.webSocketChannel().localAddress(), webSocket.path());
                listeners.add((webSocket.tls() == null ? "ws " : "wss ") + listener.webSocketChannel().localAddress()
                        + webSocket.path());
            }
            var admin = new AdminView(listener::onlinePlayers, catalog, connectionLog::activity,
                    new AdminView.Identity(version, started, transport.name(), listeners), java.time.Clock.systemUTC());
            var builtins = new BuiltinCommands(admin, new LiveAdminActions(listener), Console::print);
            for (String name : plugins.consoleCommandNames()) {
                if (BuiltinCommands.NAMES.contains(name)) {
                    LOGGER.warn("Plugin console command {} is hidden on the console by the built-in command of the "
                            + "same name; players can still run /{}", name, name);
                }
            }
            var shutdown = new Thread(() -> {
                LOGGER.info("Shutting down with {} player(s) online", listener.online().size());
                try {
                    listener.close().toCompletableFuture().join();
                } finally {
                    try {
                        if (control != null) control.close();
                    } finally {
                        try { plugins.close(); }
                        finally {
                            if (verifierWorkers != null) verifierWorkers.shutdownNow();
                            LOGGER.info("Stopped");
                        }
                    }
                }
            }, "moonbridge-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try (var console = Console.start(builtins, plugins)) {
                serverChannel.closeFuture().sync();
            } finally {
                listener.close().toCompletableFuture().join();
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdown);
                } catch (IllegalStateException ignored) {
                    // JVM shutdown is already in progress.
                }
            }
        } finally {
            listener.close().toCompletableFuture().join();
            if (verifierWorkers != null) verifierWorkers.shutdownNow();
        }
    }

    private static Map<String, String> sessionBindingSecrets(ProxyConfiguration configuration) {
        if (configuration.backendChannel() == null) return Map.of();
        Map<String, String> secrets = new HashMap<>();
        // BackendChannel already rejects two secrets for one backend name.
        configuration.backendChannel().clients().values()
                .forEach(client -> secrets.putIfAbsent(client.backendName(), client.secret()));
        return Map.copyOf(secrets);
    }

    static Path pluginDirectory(Path configPath, String configuredDirectory) {
        Path directory = Path.of(configuredDirectory);
        if (directory.isAbsolute()) return directory.normalize();
        Path absoluteConfig = configPath.toAbsolutePath().normalize();
        return absoluteConfig.getParent().resolve(directory).normalize();
    }
}

package dev.strataproxy.app;

import dev.strataproxy.core.backend.InMemoryBackendCatalog;
import dev.strataproxy.core.auth.MojangSessionVerifier;
import dev.strataproxy.core.plugin.PluginHost;
import dev.strataproxy.core.session.ProxySessionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Command line entry point for the new proxy runtime. */
public final class ProxyMain {
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
                System.out.println("Usage: strataproxy [--config <file>] | --validate-config <file> | --version");
                return 0;
            }
            if (arguments.length == 1 && arguments[0].equals("--version")) {
                String version = ProxyMain.class.getPackage().getImplementationVersion();
                System.out.println("StrataProxy " + (version == null ? "development" : version));
                return 0;
            }
            if (arguments.length == 2 && arguments[0].equals("--validate-config")) {
                var loader = new ProxyConfigurationLoader();
                Path configPath = Path.of(arguments[1]);
                var configuration = loader.load(configPath);
                loader.loadServerListStatus(configuration, configPath);
                StaticBackends.register(configuration, new InMemoryBackendCatalog());
                System.out.println("Valid core configuration and static backends: " + configuration.listen()
                        + " (enabled plugin settings are checked at startup)");
                return 0;
            }
            Path configPath;
            if (arguments.length == 0) {
                configPath = Path.of("config", "strataproxy.yml");
            } else if (arguments.length == 2 && arguments[0].equals("--config")) {
                configPath = Path.of(arguments[1]);
            } else {
                System.err.println("Invalid arguments. Use --help for usage.");
                return 2;
            }
            runProxy(configPath);
            return 0;
        } catch (Exception failure) {
            LOGGER.error("StrataProxy failed", failure);
            return 1;
        }
    }

    private static void runProxy(Path configPath) throws Exception {
        var loader = new ProxyConfigurationLoader();
        var configuration = loader.load(configPath);
        var serverListStatus = loader.loadServerListStatus(configuration, configPath);
        var catalog = new InMemoryBackendCatalog();
        StaticBackends.register(configuration, catalog);
        Duration placementTimeout = Duration.ofSeconds(configuration.plugins().initialPlacementTimeoutSeconds());
        Duration eventTimeout = Duration.ofSeconds(configuration.plugins().eventTimeoutSeconds());
        ExecutorService verifierWorkers = configuration.authentication() == ProxyConfiguration.Authentication.ONLINE_BUNGEE
                ? Executors.newFixedThreadPool(2, task -> {
                    Thread thread = new Thread(task, "strataproxy-session-verify");
                    thread.setDaemon(true);
                    return thread;
                }) : null;
        var listener = verifierWorkers == null
                ? new ProxySessionListener(configuration.listenAddress(), catalog, null, placementTimeout)
                : new ProxySessionListener(configuration.listenAddress(), catalog,
                        new MojangSessionVerifier(Duration.ofSeconds(5), verifierWorkers), placementTimeout);
        listener.setServerListStatus(serverListStatus);
        try (var plugins = new PluginHost(catalog, listener, placementTimeout, eventTimeout)) {
            listener.setMaxConnections(configuration.maxConnections());
            plugins.loadPlugins(pluginDirectory(configPath, configuration.plugins().directory()),
                    configuration.plugins().enabled());
            plugins.enable();
            listener.setEvents(plugins, eventTimeout);
            listener.setPlacement(plugins::placeInitial);
            listener.setCommandDispatcher(plugins::dispatchCommand);
            var serverChannel = listener.start().toCompletableFuture().join();
            LOGGER.info("Listening on {}", serverChannel.localAddress());
            var shutdown = new Thread(() -> {
                try {
                    listener.close().toCompletableFuture().join();
                } finally {
                    try {
                        plugins.close();
                    } finally {
                        if (verifierWorkers != null) verifierWorkers.shutdownNow();
                    }
                }
            }, "strataproxy-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
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

    static Path pluginDirectory(Path configPath, String configuredDirectory) {
        Path directory = Path.of(configuredDirectory);
        if (directory.isAbsolute()) return directory.normalize();
        Path absoluteConfig = configPath.toAbsolutePath().normalize();
        return absoluteConfig.getParent().resolve(directory).normalize();
    }
}

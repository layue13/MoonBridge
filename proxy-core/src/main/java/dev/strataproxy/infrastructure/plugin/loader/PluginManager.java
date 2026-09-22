package dev.strataproxy.infrastructure.plugin.loader;

import dev.strataproxy.plugin.PluginMetadata;
import dev.strataproxy.plugin.ProxyPlugin;
import dev.strataproxy.plugin.command.CommandRegistry;
import dev.strataproxy.plugin.event.EventBus;
import dev.strataproxy.plugin.service.PlayerService;
import dev.strataproxy.plugin.service.Scheduler;
import dev.strataproxy.plugin.service.ServerService;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.function.Function;
import java.util.jar.JarFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads plugin jars, invokes plugin lifecycle methods, and releases plugin class loaders on shutdown.
 */
public final class PluginManager implements AutoCloseable {
    private static final String DESCRIPTOR = "strataproxy-plugin.properties";

    private final CommandRegistry commands;
    private final EventBus events;
    private final PlayerService players;
    private final Function<PluginMetadata, ServerService> servers;
    private final Scheduler scheduler;
    private final Logger logger;
    private final List<LoadedPlugin> plugins = new ArrayList<>();

    /**
     * Creates a plugin manager backed by proxy services.
     *
     * @param commands command registry exposed to plugins
     * @param events event bus exposed to plugins
     * @param players player service exposed to plugins
     * @param servers server service exposed to plugins
     * @param scheduler scheduler exposed to plugins
     * @param logger logger for plugin manager diagnostics
     */
    public PluginManager(
            CommandRegistry commands,
            EventBus events,
            PlayerService players,
            ServerService servers,
            Scheduler scheduler,
            Logger logger) {
        this(commands, events, players, ignored -> servers, scheduler, logger);
    }

    /**
     * Creates a plugin manager backed by proxy services.
     *
     * @param commands command registry exposed to plugins
     * @param events event bus exposed to plugins
     * @param players player service exposed to plugins
     * @param servers server service factory bound to the current plugin metadata
     * @param scheduler scheduler exposed to plugins
     * @param logger logger for plugin manager diagnostics
     */
    public PluginManager(
            CommandRegistry commands,
            EventBus events,
            PlayerService players,
            Function<PluginMetadata, ServerService> servers,
            Scheduler scheduler,
            Logger logger) {
        this.commands = commands;
        this.events = events;
        this.players = players;
        this.servers = servers == null ? ignored -> null : servers;
        this.scheduler = scheduler;
        this.logger = logger == null ? LoggerFactory.getLogger(PluginManager.class) : logger;
    }

    /**
     * Loads every plugin jar in a directory.
     *
     * @param directory plugin directory
     * @return metadata for successfully loaded plugins
     * @throws IOException when the directory cannot be listed
     */
    public List<PluginMetadata> loadDirectory(Path directory) throws IOException {
        if (directory == null || Files.notExists(directory)) {
            return List.of();
        }
        if (!Files.isDirectory(directory)) {
            throw new IOException("plugin path is not a directory: " + directory);
        }
        try (var stream = Files.list(directory)) {
            var jars = stream
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            var loaded = new ArrayList<PluginMetadata>();
            for (var jar : jars) {
                try {
                    loaded.add(loadJar(jar));
                } catch (IOException exception) {
                    logger.warn("Skipping StrataProxy plugin jar {}", jar.toAbsolutePath().normalize(), exception);
                }
            }
            return List.copyOf(loaded);
        }
    }

    /**
     * Loads a single plugin jar.
     *
     * @param jarPath plugin jar path
     * @return loaded plugin metadata
     * @throws IOException when the jar cannot be loaded or plugin lifecycle initialization fails
     */
    public PluginMetadata loadJar(Path jarPath) throws IOException {
        var source = jarPath.toAbsolutePath().normalize();
        var classLoader = new URLClassLoader(new URL[] { source.toUri().toURL() }, ProxyPlugin.class.getClassLoader());
        ProxyPlugin plugin = null;
        try {
            var loaded = instantiate(source, classLoader);
            plugin = loaded.instance();
            loaded.instance().onLoad(new DefaultPluginContext(
                    loaded.metadata(),
                    commands,
                    events,
                    players,
                    servers.apply(loaded.metadata()),
                    scheduler));
            loaded.instance().onEnable();
            plugins.add(loaded);
            logger.info("Loaded StrataProxy plugin {} from {}", loaded.metadata().id(), source);
            return loaded.metadata();
        } catch (IOException | ReflectiveOperationException | RuntimeException | LinkageError exception) {
            if (plugin != null) {
                try {
                    plugin.onDisable();
                } catch (RuntimeException disableException) {
                    logger.warn("Plugin failed during rollback disable after load failure", disableException);
                }
            }
            closeClassLoader(classLoader);
            throw new IOException("failed to load plugin jar " + source + ": " + exception.getMessage(), exception);
        }
    }

    /**
 * Documents this public API element.
 *
     * @return metadata for currently loaded plugins
     */
    public List<PluginMetadata> plugins() {
        return plugins.stream().map(LoadedPlugin::metadata).toList();
    }

    @Override
    /** Provides close. */
    public void close() {
        var reverse = new ArrayList<>(plugins);
        java.util.Collections.reverse(reverse);
        for (var plugin : reverse) {
            try {
                plugin.instance().onDisable();
            } catch (RuntimeException exception) {
                logger.warn("Plugin {} failed during disable", plugin.metadata().id(), exception);
            } finally {
                closeClassLoader(plugin.classLoader());
            }
        }
        plugins.clear();
    }

    private LoadedPlugin instantiate(Path source, URLClassLoader classLoader)
            throws IOException, ReflectiveOperationException {
        var descriptor = readDescriptor(source);
        if (descriptor != null && !descriptor.getProperty("main", "").isBlank()) {
            var metadata = metadata(source, descriptor);
            var plugin = instantiateClass(classLoader, metadata.mainClass());
            return new LoadedPlugin(metadata, plugin, classLoader);
        }
        var loader = ServiceLoader.load(ProxyPlugin.class, classLoader);
        for (var plugin : loader) {
            var type = plugin.getClass();
            var id = type.getPackageName().isBlank() ? type.getSimpleName() : type.getPackageName();
            var metadata = new PluginMetadata(id, type.getSimpleName(), "unspecified", type.getName(), source);
            return new LoadedPlugin(metadata, plugin, classLoader);
        }
        throw new IOException("missing " + DESCRIPTOR + " with main=... and no ProxyPlugin service provider");
    }

    private static ProxyPlugin instantiateClass(ClassLoader classLoader, String className)
            throws ReflectiveOperationException {
        var type = Class.forName(className, true, classLoader);
        if (!ProxyPlugin.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException(className + " does not implement " + ProxyPlugin.class.getName());
        }
        return (ProxyPlugin) type.getDeclaredConstructor().newInstance();
    }

    private static PluginMetadata metadata(Path source, Properties properties) {
        return new PluginMetadata(
                properties.getProperty("id", "").isBlank()
                        ? source.getFileName().toString().replaceFirst("\\.jar$", "")
                        : properties.getProperty("id"),
                properties.getProperty("name", ""),
                properties.getProperty("version", ""),
                properties.getProperty("main", ""),
                source);
    }

    private static Properties readDescriptor(Path source) throws IOException {
        try (var jar = new JarFile(source.toFile())) {
            var entry = jar.getJarEntry(DESCRIPTOR);
            if (entry == null) {
                return null;
            }
            var properties = new Properties();
            try (var reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            return properties;
        }
    }

    private static void closeClassLoader(URLClassLoader classLoader) {
        try {
            classLoader.close();
        } catch (IOException ignored) {
        }
    }
}

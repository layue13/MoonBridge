package dev.moonbridge.core.plugin;

import dev.moonbridge.api.DisconnectResult;
import dev.moonbridge.api.MessageResult;
import dev.moonbridge.api.PlayerIdentity;
import dev.moonbridge.api.PlayerView;
import dev.moonbridge.api.Players;
import dev.moonbridge.api.Plugin;
import dev.moonbridge.api.PluginContext;
import dev.moonbridge.api.TransferResult;
import dev.moonbridge.core.backend.InMemoryBackendCatalog;
import dev.moonbridge.messaging.Messaging;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.time.Duration;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginLibraryTest {
    private static final String PROVIDER = "fixture.LibraryPlugin";
    private static final String HANDOFF_KEY = "moonbridge.test.plugin-context." + UUID.randomUUID();

    @TempDir
    Path temporary;

    @Test
    void pluginCanLoadAValidJarFromItsOwnDataDirectory() throws Exception {
        Path pluginDirectory = temporary.resolve("plugins-valid");
        Path library = pluginDirectory.resolve("data").resolve(PROVIDER).resolve("libs/helper.jar");
        createHelperJar(library);
        Path result = loadPlugin(pluginDirectory, library, HANDOFF_KEY);

        try (var host = new PluginHost(new InMemoryBackendCatalog(), emptyPlayers(), Duration.ofSeconds(1))) {
            host.loadPlugins(pluginDirectory, settings(library));
            host.enable();
            assertEquals("helper-loaded", Files.readString(result));
            host.close();
            assertClosedContextRejectsLibrary(host);
        }
    }

    @Test
    void pluginCannotAppendJarsOutsideItsDataDirectoryOrInvalidArchives() throws Exception {
        Path pluginDirectory = temporary.resolve("plugins-rejected");
        Path outsideLibrary = temporary.resolve("outside.jar");
        createHelperJar(outsideLibrary);
        Path externalResult = loadPlugin(pluginDirectory, outsideLibrary, HANDOFF_KEY + ".external");
        try (var host = new PluginHost(new InMemoryBackendCatalog(), emptyPlayers(), Duration.ofSeconds(1))) {
            host.loadPlugins(pluginDirectory, settings(outsideLibrary, HANDOFF_KEY + ".external"));
            host.enable();
            assertEquals("rejected", Files.readString(externalResult));
        }
        System.getProperties().remove(HANDOFF_KEY + ".external");

        Path invalidPluginDirectory = temporary.resolve("plugins-invalid");
        Path invalidLibrary = invalidPluginDirectory.resolve("data").resolve(PROVIDER).resolve("extensions/not-a-jar.jar");
        Files.createDirectories(invalidLibrary.getParent());
        Files.writeString(invalidLibrary, "not a zip archive");
        Path invalidResult = loadPlugin(invalidPluginDirectory, invalidLibrary, HANDOFF_KEY + ".invalid");
        try (var host = new PluginHost(new InMemoryBackendCatalog(), emptyPlayers(), Duration.ofSeconds(1))) {
            host.loadPlugins(invalidPluginDirectory, settings(invalidLibrary, HANDOFF_KEY + ".invalid"));
            host.enable();
            assertEquals("rejected", Files.readString(invalidResult));
        }
        System.getProperties().remove(HANDOFF_KEY + ".invalid");
    }

    @Test
    void directInstancePluginCannotAppendLibrariesAndRetainedContextExpiresAfterShutdown() throws Exception {
        var context = new java.util.concurrent.atomic.AtomicReference<PluginContext>();
        Plugin direct = new Plugin() {
            @Override public void onLoad(PluginContext pluginContext) { context.set(pluginContext); }
        };
        try (var host = new PluginHost(new InMemoryBackendCatalog(), emptyPlayers(), Duration.ofSeconds(1))) {
            host.load(List.of(direct));
            assertThrows(UnsupportedOperationException.class,
                    () -> context.get().addLibrary(Path.of("library.jar")));
        }

        Path pluginDirectory = temporary.resolve("plugins-closed");
        Path library = pluginDirectory.resolve("data").resolve(PROVIDER).resolve("libs/helper.jar");
        createHelperJar(library);
        loadPlugin(pluginDirectory, library, HANDOFF_KEY + ".closed");
        PluginHost host = new PluginHost(new InMemoryBackendCatalog(), emptyPlayers(), Duration.ofSeconds(1));
        try {
            host.loadPlugins(pluginDirectory, settings(library, HANDOFF_KEY + ".closed"));
            host.enable();
        } finally {
            host.close();
        }
        Object retained = System.getProperties().remove(HANDOFF_KEY + ".closed");
        assertNotNull(retained);
        assertThrows(IllegalStateException.class,
                () -> ((PluginContext) retained).addLibrary(library));
    }

    private static void assertClosedContextRejectsLibrary(PluginHost host) {
        Object retained = System.getProperties().remove(HANDOFF_KEY);
        assertNotNull(retained);
        assertThrows(IllegalStateException.class,
                () -> ((PluginContext) retained).addLibrary(Path.of("unused.jar")));
    }

    private static Map<String, Map<String, String>> settings(Path library) {
        return settings(library, HANDOFF_KEY);
    }

    private static Map<String, Map<String, String>> settings(Path library, String handoffKey) {
        return Map.of(PROVIDER, Map.of("library", library.toAbsolutePath().toString(), "handoffKey", handoffKey));
    }

    private Path loadPlugin(Path pluginDirectory, Path library, String handoffKey) throws Exception {
        Path dataDirectory = pluginDirectory.resolve("data").resolve(PROVIDER);
        Path sourceDirectory = temporary.resolve("src-" + UUID.randomUUID());
        Path classes = temporary.resolve("classes-" + UUID.randomUUID());
        Files.createDirectories(sourceDirectory.resolve("fixture"));
        Files.createDirectories(classes);
        Path source = sourceDirectory.resolve("fixture/LibraryPlugin.java");
        Files.writeString(source, """
                package fixture;
                import dev.moonbridge.api.Plugin;
                import dev.moonbridge.api.PluginContext;
                import java.io.IOException;
                import java.nio.file.Files;
                import java.nio.file.Path;
                public final class LibraryPlugin implements Plugin {
                    private PluginContext context;
                    public void onLoad(PluginContext context) {
                        this.context = context;
                        Path result = context.dataDirectory().resolve("result.txt");
                        try {
                            context.addLibrary(Path.of(context.settings().get("library")));
                        } catch (IOException rejected) {
                            try { Files.writeString(result, "rejected"); }
                            catch (IOException failure) { throw new IllegalStateException(failure); }
                            return;
                        }
                        try {
                            Object loaded = getClass().getClassLoader().loadClass("fixture.Helper")
                                    .getMethod("value").invoke(null);
                            Files.writeString(result, loaded.toString());
                        } catch (Exception failure) {
                            throw new IllegalStateException(failure);
                        }
                    }
                    public void onDisable() {
                        System.getProperties().put(context.settings().get("handoffKey"), context);
                    }
                }
                """);
        compile(source, classes);
        Path pluginJar = pluginDirectory.resolve("library-plugin.jar");
        Files.createDirectories(pluginJar.getParent());
        jarDirectory(classes, pluginJar, "META-INF/services/dev.moonbridge.api.Plugin",
                PROVIDER + "\n");
        return dataDirectory.resolve("result.txt");
    }

    private void createHelperJar(Path jar) throws Exception {
        Path sourceDirectory = temporary.resolve("helper-src-" + UUID.randomUUID());
        Path classes = temporary.resolve("helper-classes-" + UUID.randomUUID());
        Files.createDirectories(sourceDirectory.resolve("fixture"));
        Files.createDirectories(classes);
        Path source = sourceDirectory.resolve("fixture/Helper.java");
        Files.writeString(source, "package fixture; public final class Helper { public static String value() { return \"helper-loaded\"; } }");
        compile(source, classes);
        Files.createDirectories(jar.getParent());
        jarDirectory(classes, jar, null, null);
    }

    private static void compile(Path source, Path output) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Tests require a JDK with the Java compiler");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        List<String> arguments = List.of("--release", "25", "-classpath", compilerClasspath(), "-d",
                output.toString());
        try (var fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjects(source.toFile());
            boolean compiled = Boolean.TRUE.equals(compiler.getTask(null, fileManager, diagnostics,
                    arguments, null, units).call());
            assertTrue(compiled, () -> diagnostics.getDiagnostics().stream()
                    .map(Object::toString).collect(Collectors.joining("\n")));
        }
    }

    private static String compilerClasspath() {
        var entries = new LinkedHashSet<Path>();
        String currentClasspath = System.getProperty("java.class.path", "");
        for (String entry : currentClasspath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (!entry.isBlank()) entries.add(Path.of(entry).toAbsolutePath().normalize());
        }
        for (Class<?> type : List.of(Plugin.class, PluginContext.class, Messaging.class, Logger.class, Component.class)) {
            try {
                var source = type.getProtectionDomain().getCodeSource();
                if (source != null) entries.add(Path.of(source.getLocation().toURI()));
            } catch (URISyntaxException | NullPointerException ignored) {
                // The regular test classpath may already contain this dependency.
            }
        }
        return entries.stream().map(Path::toString).collect(Collectors.joining(java.io.File.pathSeparator));
    }

    private static void jarDirectory(Path sourceDirectory, Path jar, String extraEntry, String extraContent)
            throws IOException {
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            try (var files = Files.walk(sourceDirectory)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    output.putNextEntry(new JarEntry(sourceDirectory.relativize(file).toString().replace('\\', '/')));
                    Files.copy(file, output);
                    output.closeEntry();
                }
            }
            if (extraEntry != null) {
                output.putNextEntry(new JarEntry(extraEntry));
                output.write(extraContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
    }

    private static Players emptyPlayers() {
        return new Players() {
            @Override public Optional<PlayerView> find(PlayerIdentity identity) { return Optional.empty(); }
            @Override public List<PlayerView> online() { return List.of(); }
            @Override public CompletableFuture<TransferResult> transfer(PlayerIdentity identity, String backend) {
                return CompletableFuture.completedFuture(TransferResult.failed("test"));
            }
            @Override public CompletableFuture<MessageResult> sendMessage(PlayerIdentity identity, Component message) {
                return CompletableFuture.completedFuture(MessageResult.NOT_CONNECTED);
            }
            @Override public CompletableFuture<DisconnectResult> disconnect(PlayerIdentity identity, Component reason) {
                return CompletableFuture.completedFuture(DisconnectResult.NOT_CONNECTED);
            }
        };
    }
}

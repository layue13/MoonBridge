package dev.moonbridge.core.plugin;

import dev.moonbridge.api.Plugin;
import dev.moonbridge.api.PluginContext;
import dev.moonbridge.messaging.Messaging;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Compiles Java sources into a plugin JAR for tests that need real plugin class loaders. */
final class TestPluginJars {
    private TestPluginJars() { }

    /**
     * Writes {@code sources} (path inside the source tree to Java text), compiles them against the test classpath
     * plus {@code compileAgainst} (class directories of other test plugins) and packs them with {@code resources}.
     * Returns the class directory, so later plugins can compile against this one.
     */
    static Path build(Path workDirectory, Path jar, Map<String, String> sources, Map<String, String> resources,
                      List<Path> compileAgainst) throws IOException {
        Path sourceRoot = workDirectory.resolve("src-" + UUID.randomUUID());
        Path classes = workDirectory.resolve("classes-" + UUID.randomUUID());
        Files.createDirectories(classes);
        List<Path> files = new ArrayList<>();
        for (var source : sources.entrySet()) {
            Path file = sourceRoot.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            files.add(file);
        }
        compile(files, classes, compileAgainst);
        Files.createDirectories(jar.getParent());
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            try (var walk = Files.walk(classes)) {
                for (Path file : walk.filter(Files::isRegularFile).toList()) {
                    output.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                    Files.copy(file, output);
                    output.closeEntry();
                }
            }
            for (var resource : resources.entrySet()) {
                output.putNextEntry(new JarEntry(resource.getKey()));
                output.write(resource.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return classes;
    }

    private static void compile(List<Path> sources, Path output, List<Path> compileAgainst) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Tests require a JDK with the Java compiler");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var classpath = new ArrayList<>(List.of(testClasspath()));
        compileAgainst.forEach(path -> classpath.add(path.toString()));
        List<String> arguments = List.of("--release", "25", "-classpath",
                String.join(java.io.File.pathSeparator, classpath), "-d", output.toString());
        try (var fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var units = fileManager.getJavaFileObjectsFromPaths(sources);
            boolean compiled = Boolean.TRUE.equals(compiler.getTask(null, fileManager, diagnostics,
                    arguments, null, units).call());
            assertTrue(compiled, () -> diagnostics.getDiagnostics().stream()
                    .map(Object::toString).collect(Collectors.joining("\n")));
        }
    }

    private static String testClasspath() {
        var entries = new LinkedHashSet<Path>();
        for (String entry : System.getProperty("java.class.path", "").split(
                java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
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
}

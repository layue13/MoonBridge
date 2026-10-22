package dev.moonbridge.core.plugin;

import dev.moonbridge.api.Plugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarFile;

/** Locating plugin JARs and the provider classes they declare. */
final class PluginJars {
    private PluginJars() { }

    /** Every {@code .jar} in the directory, in filename order. */
    static List<Path> list(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    static List<String> declaredProviders(Path jar) throws IOException {
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
}

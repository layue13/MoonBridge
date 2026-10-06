package dev.moonbridge.core.plugin;

import dev.moonbridge.api.Plugin;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarFile;

    /** URLClassLoader with a supported, narrowly-scoped hook for plugin-owned extension libraries. */
    final class PluginClassLoader extends URLClassLoader {
        private final Set<Path> libraries = new HashSet<>();

        PluginClassLoader(java.net.URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        synchronized void addLibrary(Path requested, Path pluginDataDirectory) throws IOException {
            Objects.requireNonNull(requested, "jar");
            Path absoluteDataDirectory = pluginDataDirectory.toAbsolutePath().normalize();
            Files.createDirectories(absoluteDataDirectory);
            Path realDataDirectory = absoluteDataDirectory.toRealPath();
            Path absoluteRequest = requested.toAbsolutePath().normalize();
            Path candidate = requested.isAbsolute() || absoluteRequest.startsWith(absoluteDataDirectory)
                    ? absoluteRequest : realDataDirectory.resolve(requested);
            Path realJar = candidate.toRealPath();
            if (!realJar.startsWith(realDataDirectory)) {
                throw new IOException("Plugin library must be inside its data directory: " + requested);
            }
            if (!Files.isRegularFile(realJar) || !realJar.getFileName().toString().toLowerCase(Locale.ROOT)
                    .endsWith(".jar")) {
                throw new IOException("Plugin library must be a regular JAR file: " + requested);
            }
            try (JarFile ignored = new JarFile(realJar.toFile())) {
                // Opening the archive validates that it is a readable JAR before it enters the class path.
            }
            if (libraries.add(realJar)) addURL(realJar.toUri().toURL());
        }
    }

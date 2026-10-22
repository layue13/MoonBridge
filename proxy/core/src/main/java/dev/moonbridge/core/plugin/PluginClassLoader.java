package dev.moonbridge.core.plugin;

import dev.moonbridge.api.Plugin;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarFile;

    /**
     * URLClassLoader with a supported, narrowly-scoped hook for plugin-owned extension libraries, which also sees the
     * classes of the plugins it declared as dependencies. A class is looked up in the proxy API (the parent), then in
     * this plugin's own JAR and libraries, then in each dependency the same way. Own classes come before dependency
     * classes, so a library a plugin bundles is never replaced by another plugin's copy. Resources are not shared.
     * Dependencies form an acyclic graph, so lookups never wait on each other's locks in a loop.
     */
    final class PluginClassLoader extends URLClassLoader {
        static { registerAsParallelCapable(); }

        private final Set<Path> libraries = new HashSet<>();
        private final List<PluginClassLoader> dependencies;

        PluginClassLoader(java.net.URL[] urls, ClassLoader parent) {
            this(urls, parent, List.of());
        }

        PluginClassLoader(java.net.URL[] urls, ClassLoader parent, List<PluginClassLoader> dependencies) {
            super(urls, parent);
            this.dependencies = List.copyOf(dependencies);
        }

        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Class<?> type = findLoadedClass(name);
            if (type == null) {
                try {
                    type = getParent().loadClass(name);
                } catch (ClassNotFoundException notShared) {
                    type = loadOwnOrFromDependencies(name);
                    if (type == null) throw new ClassNotFoundException(name);
                }
            }
            if (resolve) resolveClass(type);
            return type;
        }

        /** This plugin's own class, or a dependency's; null when none of them defines it. */
        private Class<?> loadOwnOrFromDependencies(String name) {
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                if (type != null) return type;
                try {
                    return findClass(name);
                } catch (ClassNotFoundException notOwn) {
                    // Fall through to the declared dependencies.
                }
            }
            for (PluginClassLoader dependency : dependencies) {
                Class<?> type = dependency.loadOwnOrFromDependencies(name);
                if (type != null) return type;
            }
            return null;
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

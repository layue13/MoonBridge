package dev.moonbridge.core.plugin;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.moonbridge.api.PluginInfo;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/**
 * The optional {@code META-INF/moonbridge-plugin.yml} of a plugin JAR: an id other plugins can depend on, a version,
 * the entry class and the plugins this one needs. A JAR without it is loaded as before, through
 * {@code META-INF/services}, and takes no part in dependencies.
 */
record PluginDescriptor(String id, String version, String main, List<Dependency> dependencies) {
    static final String RESOURCE = "META-INF/moonbridge-plugin.yml";
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final Pattern CLASS_NAME =
            Pattern.compile("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*(\\.[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)*");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    record Dependency(String id, boolean optional) {
        @JsonCreator Dependency(@JsonProperty("id") String id, @JsonProperty("optional") Boolean optional) {
            this(requireId(id, "dependency id"), optional != null && optional);
        }
    }

    PluginDescriptor {
        requireId(id, "id");
        requireVersion(version);
        requireMain(main);
        dependencies = dependencies == null ? List.of() : new java.util.ArrayList<>(dependencies);
        var seen = new HashSet<String>();
        for (Dependency dependency : dependencies) {
            if (dependency == null) throw new IllegalArgumentException("plugin " + id + " has an empty dependency entry");
            if (dependency.id().equals(id)) throw new IllegalArgumentException("plugin " + id + " depends on itself");
            if (!seen.add(dependency.id())) {
                throw new IllegalArgumentException("plugin " + id + " lists dependency " + dependency.id() + " twice");
            }
        }
        dependencies = List.copyOf(dependencies);
    }

    /** Reads the JAR's descriptor; empty when the JAR has none. */
    static Optional<PluginDescriptor> read(Path jar) throws IOException {
        try (JarFile archive = new JarFile(jar.toFile())) {
            var entry = archive.getJarEntry(RESOURCE);
            if (entry == null) return Optional.empty();
            try (var input = archive.getInputStream(entry)) {
                PluginDescriptor descriptor = YAML.readValue(input, PluginDescriptor.class);
                if (descriptor == null) throw new IOException(RESOURCE + " is empty");
                return Optional.of(descriptor);
            } catch (IOException | IllegalArgumentException invalid) {
                throw new IOException("Invalid " + RESOURCE + " in " + jar.getFileName() + ": " + invalid.getMessage(),
                        invalid);
            }
        }
    }

    PluginInfo info() {
        return new PluginInfo(id, version, dependencies.stream().map(Dependency::id).toList());
    }

    private static String requireId(String id, String field) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException(field + " must match " + ID.pattern() + ": " + id);
        }
        return id;
    }

    private static String requireVersion(String version) {
        if (version == null || version.isBlank() || version.length() > 64 || !version.equals(version.trim())) {
            throw new IllegalArgumentException("version must be 1-64 characters without surrounding spaces");
        }
        return version;
    }

    private static String requireMain(String main) {
        if (main == null || !CLASS_NAME.matcher(main).matches()) {
            throw new IllegalArgumentException("main must be a fully qualified class name: " + main);
        }
        return main;
    }
}

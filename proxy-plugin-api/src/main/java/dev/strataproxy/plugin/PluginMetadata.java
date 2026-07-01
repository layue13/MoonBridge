package dev.strataproxy.plugin;

import java.nio.file.Path;

/**
 * Metadata describing a loaded plugin.
 *
 * @param id stable lowercase plugin identifier
 * @param name display name
 * @param version plugin version string
 * @param mainClass fully qualified entrypoint class for external plugins
 * @param source plugin archive or directory, or {@code null} for built-in plugins
 */
public record PluginMetadata(String id, String name, String version, String mainClass, Path source) {
    public PluginMetadata {
        id = normalizeId(id);
        name = name == null || name.isBlank() ? id : name.trim();
        version = version == null || version.isBlank() ? "unspecified" : version.trim();
        mainClass = mainClass == null ? "" : mainClass.trim();
    }

    /**
     * Creates metadata for a plugin provided by the proxy itself.
     *
     * @param id stable plugin identifier
     * @param name display name
     * @return built-in plugin metadata
     */
    public static PluginMetadata builtin(String id, String name) {
        return new PluginMetadata(id, name, "builtin", "", null);
    }

    private static String normalizeId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("plugin id must not be blank");
        }
        var normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("plugin id contains unsupported characters: " + value);
        }
        return normalized;
    }
}

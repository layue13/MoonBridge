package dev.strataproxy.plugin;

import java.nio.file.Path;

public record PluginMetadata(String id, String name, String version, String mainClass, Path source) {
    public PluginMetadata {
        id = normalizeId(id);
        name = name == null || name.isBlank() ? id : name.trim();
        version = version == null || version.isBlank() ? "unspecified" : version.trim();
        mainClass = mainClass == null ? "" : mainClass.trim();
    }

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

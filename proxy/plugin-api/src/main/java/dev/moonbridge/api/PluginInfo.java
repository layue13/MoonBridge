package dev.moonbridge.api;

import java.util.List;
import java.util.Objects;

/**
 * A plugin that declared itself in {@code META-INF/moonbridge-plugin.yml}. Plugins without a descriptor have no id,
 * so other plugins can neither find them here nor depend on them.
 *
 * @param dependencies ids this plugin declared, required and optional, in declaration order
 */
public record PluginInfo(String id, String version, List<String> dependencies) {
    public PluginInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        dependencies = List.copyOf(dependencies);
    }
}

package dev.moonbridge.core.plugin;

/** Indicates a plugin failed while receiving its initial context. */
public final class PluginLoadException extends RuntimeException {
    public PluginLoadException(String pluginId, Throwable cause) {
        super("Plugin failed to load: " + pluginId, cause);
    }
}

package dev.strataproxy.plugin;

/**
 * Entry point implemented by proxy plugins.
 *
 * <p>Implementations are loaded, enabled, and disabled by the plugin manager. Lifecycle methods are invoked in order
 * on a single plugin instance.</p>
 */
public interface ProxyPlugin {
    /**
     * Called after the plugin instance is created and before it is enabled.
     *
     * @param context proxy services available to the plugin
     */
    default void onLoad(PluginContext context) {
    }

    /**
     * Called when the plugin should register commands, listeners, or tasks.
     */
    default void onEnable() {
    }

    /**
     * Called before the plugin is unloaded so it can release resources.
     */
    default void onDisable() {
    }
}

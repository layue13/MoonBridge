package dev.strataproxy.api;

import java.util.Optional;

/** Lifecycle contract for a proxy plugin. */
public interface Plugin {
    void onLoad(PluginContext context);

    default void onEnable() {
    }

    default void onDisable() {
    }

    /**
     * Returns this plugin's optional handler for the proxy's single initial placement hook.
     * The proxy applies its configured timeout to the returned asynchronous stage.
     */
    default Optional<InitialPlacementHandler> initialPlacementHandler() {
        return Optional.empty();
    }
}

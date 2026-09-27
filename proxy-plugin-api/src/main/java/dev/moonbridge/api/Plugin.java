package dev.moonbridge.api;

import java.util.Optional;
import dev.moonbridge.api.permission.PermissionProvider;

/** Lifecycle contract for a proxy plugin. */
public interface Plugin {
    void onLoad(PluginContext context);

    default void onEnable() {
    }

    /**
     * Called after pending placement and access checks are ended and callback workers are asked to stop.
     * A callback that ignores interruption may still be running during this method. The proxy
     * allows a total of ten seconds for all plugins to finish their disable hooks.
     */
    default void onDisable() {
    }

    /**
     * Returns this plugin's optional handler for the proxy's single initial placement hook.
     * At most one enabled plugin may provide this handler; startup fails if multiple plugins do.
     * When one is provided it owns the routing decision, including rejection and failures. The
     * configured static initial-routing list is consulted only when no plugin provides a handler.
     * The proxy applies the initial-routing timeout to the returned asynchronous stage.
     */
    default Optional<InitialPlacementHandler> initialPlacementHandler() {
        return Optional.empty();
    }

    /** Returns this plugin's optional player permission provider. */
    default Optional<PermissionProvider> permissionProvider() {
        return Optional.empty();
    }
}

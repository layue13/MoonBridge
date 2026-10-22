package dev.moonbridge.api.permission;

import dev.moonbridge.api.PlayerIdentity;

/** Permission checks backed by the proxy's configured permission provider. */
public interface Permissions {
    /** Checks using the subject's current player context. */
    PermissionResult check(PlayerIdentity identity, String node);

    /** Convenience check that treats only ALLOW as granted, matching common proxy permission APIs. */
    default boolean hasPermission(PlayerIdentity identity, String node) {
        return check(identity, node) == PermissionResult.ALLOW;
    }

    /** Checks using an explicit context, which is merged with the subject's current context. */
    PermissionResult check(PlayerIdentity identity, String node, PermissionContext context);

    /** Convenience check that treats undefined and unavailable results as denied. */
    default boolean hasPermission(PlayerIdentity identity, String node, PermissionContext context) {
        return check(identity, node, context) == PermissionResult.ALLOW;
    }
}

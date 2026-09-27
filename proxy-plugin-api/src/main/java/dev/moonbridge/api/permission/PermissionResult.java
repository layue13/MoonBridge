package dev.moonbridge.api.permission;

/** Result exposed by MoonBridge, including whether a usable permission subject is ready. */
public enum PermissionResult {
    ALLOW,
    DENY,
    UNDEFINED,
    UNAVAILABLE
}

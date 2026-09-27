package dev.moonbridge.api.permission;

/** A provider's cached permission answer. UNDEFINED preserves inheritance/default semantics. */
public enum PermissionDecision {
    ALLOW,
    DENY,
    UNDEFINED
}

package dev.moonbridge.core.auth;

/** A bounded property returned by the session server, with optional Mojang signature. */
public record ProfileProperty(String name, String value, String signature) {
    public ProfileProperty {
        if (name == null || name.isBlank() || name.length() > 64) {
            throw new IllegalArgumentException("profile property name is required and limited to 64 characters");
        }
        if (value == null || value.length() > 32 * 1024) {
            throw new IllegalArgumentException("profile property value is required and limited to 32768 characters");
        }
        if (signature != null && signature.length() > 8192) {
            throw new IllegalArgumentException("profile property signature exceeds 8192 characters");
        }
    }
}

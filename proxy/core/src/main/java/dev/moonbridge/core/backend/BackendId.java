package dev.moonbridge.core.backend;

import java.util.Objects;

/** Stable identity of a backend definition in the catalog. */
public record BackendId(String value) {
    public BackendId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Backend id must not be blank");
        }
    }
}

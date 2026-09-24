package dev.strataproxy.core.backend;

import java.util.Objects;

/** Identifies the owner process and its restart/reload generation. */
public record BackendOwner(String id, long instanceGeneration) {
    public BackendOwner {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Owner id must not be blank");
        }
        if (instanceGeneration < 0) {
            throw new IllegalArgumentException("Instance generation must be non-negative");
        }
    }
}

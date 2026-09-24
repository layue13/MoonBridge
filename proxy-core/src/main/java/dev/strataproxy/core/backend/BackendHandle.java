package dev.strataproxy.core.backend;

import java.util.Objects;

/** Opaque identity for one catalog generation of a backend. */
public record BackendHandle(BackendId id, long generation) {
    public BackendHandle {
        Objects.requireNonNull(id, "id");
        if (generation < 1) {
            throw new IllegalArgumentException("Catalog generation must be positive");
        }
    }
}

package dev.moonbridge.core.backend;

import java.net.URI;
import java.util.Objects;
import java.util.Map;

/** Immutable point-in-time directory snapshot. */
public record BackendView(
        BackendHandle handle,
        BackendOwner owner,
        URI address,
        Map<String, String> tags,
        Map<String, String> metadata) {
    public BackendView {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(address, "address");
        tags = Map.copyOf(Objects.requireNonNull(tags, "tags"));
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
    }
}

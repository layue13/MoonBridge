package dev.strataproxy.core.backend;

import java.net.URI;
import java.util.Objects;
import java.util.Map;

/** Immutable point-in-time directory snapshot. */
public record BackendView(
        BackendHandle handle,
        BackendOwner owner,
        URI address,
        int capacity,
        int connectedPlayers,
        int reservedCapacity,
        Map<String, String> tags,
        Map<String, String> metadata) {
    public BackendView {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(address, "address");
        tags = Map.copyOf(Objects.requireNonNull(tags, "tags"));
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        if (capacity < 0 || connectedPlayers < 0 || reservedCapacity < 0) {
            throw new IllegalArgumentException("Capacity values must be non-negative");
        }
    }

    public int availableUnits() {
        return Math.max(0, capacity - connectedPlayers - reservedCapacity);
    }
}

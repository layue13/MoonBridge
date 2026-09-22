package dev.strataproxy.backend.api;

import java.util.UUID;

/** Platform-neutral reference to a player currently connected to a backend. */
public final class BackendPlayer {
    private final UUID uniqueId;
    private final String name;

    public BackendPlayer(UUID uniqueId, String name) {
        if (uniqueId == null && (name == null || name.trim().isEmpty())) {
            throw new IllegalArgumentException("a player UUID or name is required");
        }
        this.uniqueId = uniqueId;
        this.name = name == null ? "" : name.trim();
    }

    public UUID uniqueId() { return uniqueId; }
    public String name() { return name; }
}

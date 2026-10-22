package dev.moonbridge.api;

import java.util.Objects;
import java.util.UUID;

/** Identifies one authenticated player connection, including across reconnects. */
public record PlayerIdentity(UUID playerId, long connectionId) {
    public PlayerIdentity {
        Objects.requireNonNull(playerId, "playerId");
        if (connectionId < 0) {
            throw new IllegalArgumentException("connectionId must be non-negative");
        }
    }
}

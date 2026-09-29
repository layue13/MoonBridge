package dev.moonbridge.api.profile;

import java.util.Objects;

/** Exact backend registration selected for one profile admission or transfer. */
public record ProfileBackend(String name, long registrationGeneration, String ownerId, long nodeEpoch) {
    public ProfileBackend {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(ownerId, "ownerId");
        if (name.isBlank() || ownerId.isBlank()) throw new IllegalArgumentException("backend identity is incomplete");
        if (registrationGeneration < 1 || nodeEpoch < 0)
            throw new IllegalArgumentException("backend generations are invalid");
    }
}

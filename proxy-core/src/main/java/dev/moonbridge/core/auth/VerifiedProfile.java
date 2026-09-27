package dev.moonbridge.core.auth;

import java.util.UUID;
import java.util.List;

/** Identity returned by a successful session server hasJoined response. */
public record VerifiedProfile(UUID uuid, String username, List<ProfileProperty> properties) {
    public VerifiedProfile {
        if (uuid == null) throw new IllegalArgumentException("uuid is required");
        if (username == null || username.isBlank()) throw new IllegalArgumentException("username is required");
        if (properties == null) throw new IllegalArgumentException("properties are required");
        properties = List.copyOf(properties);
    }
}

package dev.moonbridge.api.profile;

import dev.moonbridge.api.PlayerIdentity;
import java.util.Objects;
import java.util.UUID;

/** Immutable exact-session request for an initial admission or cross-backend handoff. */
public record ProfileHandoffRequest(PlayerIdentity player, String username, UUID proxyEpoch,
                                    UUID operationId, UUID attemptId,
                                    ProfileBackend source, ProfileBackend target) {
    public ProfileHandoffRequest {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(proxyEpoch, "proxyEpoch");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(target, "target");
        if (username.isBlank()) throw new IllegalArgumentException("username must not be blank");
    }

    public boolean isAdmission() { return source == null; }
}

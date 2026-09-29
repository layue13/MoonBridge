package dev.moonbridge.api.profile;

import dev.moonbridge.api.PlayerIdentity;
import java.util.Objects;
import java.util.UUID;

/** Exact currently routed proxy session used for read-only profile state queries. */
public record ProfileSession(PlayerIdentity player, UUID proxyEpoch, ProfileBackend backend) {
    public ProfileSession {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(proxyEpoch, "proxyEpoch");
        Objects.requireNonNull(backend, "backend");
    }
}

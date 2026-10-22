package dev.moonbridge.api;

import java.util.Objects;
import java.util.Optional;

/** Immutable view of a player connection known to the proxy. */
public record PlayerView(PlayerIdentity identity, String username, Optional<String> currentServer) {
    public PlayerView {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(username, "username");
        currentServer = Objects.requireNonNull(currentServer, "currentServer");
        if (username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        currentServer.ifPresent(server -> {
            if (server.isBlank()) {
                throw new IllegalArgumentException("currentServer must not be blank");
            }
        });
    }

    public PlayerView(PlayerIdentity identity, String username, String currentServer) {
        this(identity, username, Optional.ofNullable(currentServer));
    }
}

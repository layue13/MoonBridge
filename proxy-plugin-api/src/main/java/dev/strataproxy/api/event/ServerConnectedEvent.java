package dev.strataproxy.api.event;

import dev.strataproxy.api.PlayerView;
import java.util.Objects;
import java.util.Optional;

/**
 * A player's initial publication after backend login validation, or a committed backend change.
 * The previous server is empty on the first connection. The immutable player view names the new
 * server; Forge negotiation and gameplay readiness may still be pending.
 */
public record ServerConnectedEvent(PlayerView player, Optional<String> previousServer)
        implements Event<Void> {
    public ServerConnectedEvent {
        Objects.requireNonNull(player, "player");
        previousServer = Objects.requireNonNull(previousServer, "previousServer");
        if (player.currentServer().isEmpty()) {
            throw new IllegalArgumentException("player must have a current server");
        }
        previousServer.ifPresent(server -> {
            if (server.isBlank()) {
                throw new IllegalArgumentException("previousServer must not be blank");
            }
        });
    }
}

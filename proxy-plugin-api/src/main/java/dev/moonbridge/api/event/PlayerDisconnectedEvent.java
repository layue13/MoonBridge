package dev.moonbridge.api.event;

import dev.moonbridge.api.PlayerView;
import java.util.Objects;

/**
 * The final snapshot of a previously published player connection. Emitted once on session close;
 * rejected logins do not emit this event. As with other notifications, delivery is best effort.
 */
public record PlayerDisconnectedEvent(PlayerView player) implements Event<Void> {
    public PlayerDisconnectedEvent {
        Objects.requireNonNull(player, "player");
    }
}

package dev.moonbridge.api.event;

import dev.moonbridge.api.AccessDecision;
import dev.moonbridge.api.PlayerView;
import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * An admission check after the core has established a player identity and before initial backend
 * placement. The remote address is the actual TCP peer. {@code authenticated} is true only after
 * the core has successfully verified the player's identity with online-mode authentication. In
 * offline mode it is false: the name and derived UUID are not a verified account identity. This
 * event does not perform or replace authentication; it lets plugins make a post-authentication
 * admission decision.
 */
public record PlayerAdmissionEvent(
        PlayerView player, InetSocketAddress remoteAddress, boolean authenticated)
        implements Event<AccessDecision> {
    public PlayerAdmissionEvent {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(remoteAddress, "remoteAddress");
    }
}

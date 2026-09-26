package dev.strataproxy.api.event;

import dev.strataproxy.api.PlayerView;
import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * A login after identity establishment and before initial backend placement. The remote address
 * is the actual TCP peer. When {@code authenticated} is false, the name and derived UUID are
 * supplied by an offline login and must not be treated as a verified account identity.
 */
public record LoginEvent(PlayerView player, InetSocketAddress remoteAddress, boolean authenticated)
        implements AccessEvent {
    public LoginEvent {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(remoteAddress, "remoteAddress");
    }
}

package dev.strataproxy.api;

import java.net.InetSocketAddress;
import java.util.Objects;

/** Immutable identity and peer details available before initial backend placement. */
public record LoginRequest(PlayerView player, InetSocketAddress remoteAddress, boolean authenticated) {
    public LoginRequest {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(remoteAddress, "remoteAddress");
    }
}

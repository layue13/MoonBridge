package dev.moonbridge.api.event;

import dev.moonbridge.api.AccessDecision;
import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * A newly accepted TCP connection, before protocol parsing or authentication. The address is the
 * actual socket peer. This event also applies to status queries; player identity is not yet known.
 */
public record ConnectionAdmissionEvent(InetSocketAddress remoteAddress)
        implements Event<AccessDecision> {
    public ConnectionAdmissionEvent {
        Objects.requireNonNull(remoteAddress, "remoteAddress");
    }
}

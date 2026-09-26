package dev.strataproxy.api.event;

import java.net.InetSocketAddress;
import java.util.Objects;

/** A newly accepted client connection, before login identity is known. */
public record ConnectionEvent(InetSocketAddress remoteAddress) implements AccessEvent {
    public ConnectionEvent {
        Objects.requireNonNull(remoteAddress, "remoteAddress");
    }
}

package dev.strataproxy.api;

import java.net.URI;
import java.util.Objects;
import java.util.Map;

/**
 * Immutable directory snapshot. Player counts describe sessions attached through this proxy
 * instance and this registration generation; they do not measure backend CPU, TPS, memory,
 * or players connected through other proxies.
 */
public record ServerView(String name, URI address, Map<String, String> tags, Map<String, String> metadata,
                         int capacity, int connectedPlayers, int reservedCapacity) {
    public ServerView {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(address, "address");
        tags = Map.copyOf(Objects.requireNonNull(tags, "tags"));
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        if (name.isBlank() || !address.isAbsolute()) {
            throw new IllegalArgumentException("name must not be blank and address must be absolute");
        }
        if (capacity < 0 || connectedPlayers < 0 || reservedCapacity < 0) {
            throw new IllegalArgumentException("capacity values must be non-negative");
        }
    }
}

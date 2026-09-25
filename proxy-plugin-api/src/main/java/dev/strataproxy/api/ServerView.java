package dev.strataproxy.api;

import java.net.URI;
import java.util.Objects;
import java.util.Map;

/** Immutable snapshot of a registered backend. */
public record ServerView(String name, URI address, Map<String, String> tags, Map<String, String> metadata) {
    public ServerView {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(address, "address");
        tags = Map.copyOf(Objects.requireNonNull(tags, "tags"));
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        if (name.isBlank() || !address.isAbsolute()) {
            throw new IllegalArgumentException("name must not be blank and address must be absolute");
        }
    }
}

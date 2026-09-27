package dev.moonbridge.api;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

/** Plugin-owned configuration for one dynamically registered backend. */
public record ServerDefinition(String name, URI address, Map<String, String> tags,
                               Map<String, String> metadata) {
    public ServerDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(address, "address");
        tags = Map.copyOf(Objects.requireNonNull(tags, "tags"));
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        String path = address.getRawPath();
        if (name.isBlank() || !"tcp".equalsIgnoreCase(address.getScheme())
                || address.getHost() == null || address.getPort() < 1 || address.getPort() > 65535
                || address.getRawUserInfo() != null || (path != null && !path.isEmpty())
                || address.getRawQuery() != null || address.getRawFragment() != null) {
            throw new IllegalArgumentException("server requires a name and tcp://host:port address");
        }
    }
}

package dev.strataproxy.core.backend;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

/** Address and capacity supplied by a registration owner. */
public record BackendRegistration(
        BackendId id,
        BackendOwner owner,
        URI address,
        int capacity,
        Map<String, String> tags,
        Map<String, String> metadata) {
    public BackendRegistration {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(address, "address");
        tags = Map.copyOf(Objects.requireNonNull(tags, "tags"));
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        String path = address.getRawPath();
        if (!"tcp".equalsIgnoreCase(address.getScheme()) || address.getHost() == null
                || address.getPort() < 1 || address.getPort() > 65535
                || address.getRawUserInfo() != null || (path != null && !path.isEmpty())
                || address.getRawQuery() != null || address.getRawFragment() != null) {
            throw new IllegalArgumentException("Backend address must be tcp://host:port");
        }
        if (capacity < 0) {
            throw new IllegalArgumentException("Capacity must be non-negative");
        }
    }

    public BackendRegistration(BackendId id, BackendOwner owner, URI address, int capacity) {
        this(id, owner, address, capacity, Map.of(), Map.of());
    }
}

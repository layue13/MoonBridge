package dev.moonbridge.core.backend;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

/** Address and plugin data supplied by a registration owner. */
public record BackendRegistration(
        BackendId id,
        BackendOwner owner,
        URI address,
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
    }

    public BackendRegistration(BackendId id, BackendOwner owner, URI address) {
        this(id, owner, address, Map.of(), Map.of());
    }
}

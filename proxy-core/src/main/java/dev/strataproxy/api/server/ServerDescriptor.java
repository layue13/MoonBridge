package dev.strataproxy.api.server;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable configuration for a backend server.
 *
 * <p>Descriptors carry only static routing data. Runtime state such as health, current player count, and drain state
 * lives on {@link RegisteredServer}.</p>
 *
 * @param name unique backend name used by routes, admin APIs, and diagnostics
 * @param address socket address the proxy connects to
 * @param tags free-form routing labels such as region, mode, or shard
 * @param capabilities feature flags that clients or routes may require
 * @param protocolRange supported Minecraft protocol versions
 * @param weight reserved relative weight for a future route policy
 * @param softCapacity reserved player threshold for a future route policy
 * @param hardCapacity player count at which new routing is rejected; zero means unlimited
 * @param drainMode initial drain flag for this server
 * @param metadata extra routing and operational metadata, including optional {@code host} or {@code route} aliases
 */
public record ServerDescriptor(
        String name,
        InetSocketAddress address,
        Set<String> tags,
        Set<ServerCapability> capabilities,
        ProtocolRange protocolRange,
        int weight,
        int softCapacity,
        int hardCapacity,
        boolean drainMode,
        Map<String, String> metadata) {
    /**
     * Validates and normalizes record components.
     */
    public ServerDescriptor {
        name = requireName(name);
        address = Objects.requireNonNull(address, "address");
        tags = Set.copyOf(tags == null ? Set.of() : tags);
        capabilities = Set.copyOf(capabilities == null ? Set.of() : capabilities);
        protocolRange = Objects.requireNonNull(protocolRange, "protocolRange");
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
        if (weight <= 0) {
            throw new IllegalArgumentException("weight must be positive");
        }
        if (softCapacity < 0 || hardCapacity < 0) {
            throw new IllegalArgumentException("capacity values must be non-negative");
        }
    }

    private static String requireName(String name) {
        var value = Objects.requireNonNull(name, "name").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return value;
    }

    /**
     * Tests whether this descriptor declares a backend capability.
     *
     * @param capability feature to check
     * @return {@code true} when the capability is present
     */
    public boolean supports(ServerCapability capability) {
        return capabilities.contains(capability);
    }
}

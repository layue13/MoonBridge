package dev.strataproxy.api.server;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

    public boolean supports(ServerCapability capability) {
        return capabilities.contains(capability);
    }
}

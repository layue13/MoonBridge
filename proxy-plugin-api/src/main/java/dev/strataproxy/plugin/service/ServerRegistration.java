package dev.strataproxy.plugin.service;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Plugin request to register or replace a backend server.
 *
 * @param name unique backend name
 * @param address backend socket address
 * @param tags routing tags
 * @param capabilities backend capability names matching StrataProxy server capabilities
 * @param protocolRange accepted protocol range
 * @param weight relative routing weight
 * @param softCapacity player count where routing preference starts decreasing
 * @param hardCapacity player count where new routing is rejected; zero means unlimited
 * @param drainMode initial drain mode
 * @param metadata extra routing and operational metadata
 * @param persistence whether the mutation should be written to the registry store
 */
public record ServerRegistration(
        String name,
        InetSocketAddress address,
        Set<String> tags,
        Set<String> capabilities,
        ServerProtocolRange protocolRange,
        int weight,
        int softCapacity,
        int hardCapacity,
        boolean drainMode,
        Map<String, String> metadata,
        ServerPersistence persistence) {
    /**
     * Creates a runtime-only backend registration with default routing settings.
     *
     * @param name unique backend name
     * @param address backend socket address
     */
    public ServerRegistration(String name, InetSocketAddress address) {
        this(name, address, Set.of(), Set.of(), ServerProtocolRange.any(), 100, 0, 0, false, Map.of(), ServerPersistence.EPHEMERAL);
    }

    /**
     * Validates and normalizes record components.
     */
    public ServerRegistration {
        name = requireName(name);
        address = Objects.requireNonNull(address, "address");
        tags = Set.copyOf(tags == null ? Set.of() : tags);
        capabilities = Set.copyOf(capabilities == null ? Set.of() : capabilities);
        protocolRange = protocolRange == null ? ServerProtocolRange.any() : protocolRange;
        if (weight <= 0) {
            throw new IllegalArgumentException("weight must be positive");
        }
        if (softCapacity < 0 || hardCapacity < 0) {
            throw new IllegalArgumentException("capacity values must be non-negative");
        }
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
        persistence = persistence == null ? ServerPersistence.EPHEMERAL : persistence;
    }

    private static String requireName(String name) {
        var value = Objects.requireNonNull(name, "name").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return value;
    }
}

package dev.strataproxy.plugin.service;

import java.util.Objects;

/**
 * Inclusive Minecraft protocol-version range accepted by a plugin-registered backend.
 *
 * @param minProtocol lowest accepted protocol version
 * @param maxProtocol highest accepted protocol version
 * @param displayName human-readable version label for diagnostics
 */
public record ServerProtocolRange(int minProtocol, int maxProtocol, String displayName) {
    /**
     * Validates and normalizes record components.
     */
    public ServerProtocolRange {
        if (minProtocol < 0 || maxProtocol < 0) {
            throw new IllegalArgumentException("protocol versions must be non-negative");
        }
        if (minProtocol > maxProtocol) {
            throw new IllegalArgumentException("minProtocol must be <= maxProtocol");
        }
        displayName = Objects.requireNonNull(displayName, "displayName");
    }

    /**
     * Creates a range that accepts every non-negative protocol version.
     *
     * @return unrestricted protocol range
     */
    public static ServerProtocolRange any() {
        return new ServerProtocolRange(0, Integer.MAX_VALUE, "any");
    }
}

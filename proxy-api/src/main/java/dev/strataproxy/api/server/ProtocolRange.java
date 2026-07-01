package dev.strataproxy.api.server;

import java.util.Objects;

/**
 * Inclusive Minecraft protocol-version range supported by a backend server.
 *
 * @param minProtocol lowest accepted protocol version
 * @param maxProtocol highest accepted protocol version
 * @param displayName human-readable version label for dashboards and diagnostics
 */
public record ProtocolRange(int minProtocol, int maxProtocol, String displayName) {
    /**
     * Validates and normalizes record components.
     */
    public ProtocolRange {
        if (minProtocol < 0 || maxProtocol < 0) {
            throw new IllegalArgumentException("protocol versions must be non-negative");
        }
        if (minProtocol > maxProtocol) {
            throw new IllegalArgumentException("minProtocol must be <= maxProtocol");
        }
        displayName = Objects.requireNonNull(displayName, "displayName");
    }

    /**
     * Tests whether a client protocol version can be routed to a server with this range.
     *
     * @param protocolVersion numeric Minecraft protocol version from the handshake
     * @return {@code true} when the version is inside the inclusive range
     */
    public boolean accepts(int protocolVersion) {
        return protocolVersion >= minProtocol && protocolVersion <= maxProtocol;
    }
}

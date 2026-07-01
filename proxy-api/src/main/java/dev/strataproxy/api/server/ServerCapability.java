package dev.strataproxy.api.server;

/**
 * Optional backend features that routing rules and plugins can require.
 */
public enum ServerCapability {
    /** Backend can complete Forge-specific handshakes. */
    FORGE_HANDSHAKE,
    /** Backend can complete Fabric-specific handshakes. */
    FABRIC_HANDSHAKE,
    /** Backend accepts large plugin payloads or packet frames. */
    LARGE_PAYLOAD,
    /** Backend supports modern Velocity-style player forwarding. */
    MODERN_FORWARDING,
    /** Backend supports legacy BungeeCord-style forwarding. */
    LEGACY_FORWARDING,
    /** Backend understands Minecraft transfer packets. */
    TRANSFER_PACKET,
    /** Backend exposes a health endpoint or probe target. */
    HEALTH_PROBE
}

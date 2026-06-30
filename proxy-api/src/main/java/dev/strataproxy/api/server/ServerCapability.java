package dev.strataproxy.api.server;

public enum ServerCapability {
    FORGE_HANDSHAKE,
    FABRIC_HANDSHAKE,
    LARGE_PAYLOAD,
    MODERN_FORWARDING,
    LEGACY_FORWARDING,
    TRANSFER_PACKET,
    HEALTH_PROBE
}

package dev.strataproxy.network;

/**
 * Parsed Minecraft handshake packet.
 *
 * @param protocolVersion client protocol version
 * @param requestedHost host requested by the client
 * @param requestedPort port requested by the client
 * @param nextState requested next protocol state id
 */
public record MinecraftHandshake(
        int protocolVersion,
        String requestedHost,
        int requestedPort,
        int nextState) {
    /**
     * Validates host and port values parsed from the handshake packet.
     */
    public MinecraftHandshake {
        if (requestedHost == null || requestedHost.isBlank()) {
            throw new IllegalArgumentException("requestedHost must not be blank");
        }
        if (requestedPort < 0 || requestedPort > 65535) {
            throw new IllegalArgumentException("requestedPort must fit unsigned short");
        }
    }

    boolean legacyForgeClientMarker() {
        return requestedHost.contains("\0FML\0");
    }
}

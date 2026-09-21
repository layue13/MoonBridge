package dev.strataproxy.protocol;

/**
 * Minecraft connection protocol state.
 */
public enum ProtocolState {
    /** Initial handshake state. */
    HANDSHAKE,
    /** Server list ping/status state. */
    STATUS,
    /** Login and authentication state. */
    LOGIN,
    /** Configuration state used by modern protocol versions before play. */
    CONFIGURATION,
    /** In-game play state. */
    PLAY
}

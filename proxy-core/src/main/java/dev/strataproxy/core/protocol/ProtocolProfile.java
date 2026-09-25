package dev.strataproxy.core.protocol;

/** Wire limits and packet rules for one explicitly supported Minecraft protocol. */
public record ProtocolProfile(
        int protocolVersion,
        int maxFrameBytes,
        int maxHandshakeHostCharacters,
        int maxLoginNameCharacters) {

    public static final int PROTOCOL_1_7_10 = 5;
    // The largest accepted handshake host and encryption response each fit well below this limit.
    public static final int MAX_LOGIN_FRAME_BYTES = 4096;
    // 1.7.10's length decoder accepts at most three VarInt bytes.
    private static final ProtocolProfile MINECRAFT_1_7_10 = new ProtocolProfile(5, 0x1FFFFF, 255, 16);

    public ProtocolProfile {
        if (protocolVersion < 0) throw new IllegalArgumentException("protocolVersion must be non-negative");
        if (maxFrameBytes < 1) throw new IllegalArgumentException("maxFrameBytes must be positive");
        if (maxHandshakeHostCharacters < 1 || maxLoginNameCharacters < 1) {
            throw new IllegalArgumentException("string limits must be positive");
        }
    }

    public static ProtocolProfile minecraft1710() {
        return MINECRAFT_1_7_10;
    }
}

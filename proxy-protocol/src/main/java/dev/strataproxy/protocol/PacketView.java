package dev.strataproxy.protocol;

public record PacketView(
        PacketDirection direction,
        ProtocolState state,
        int protocolVersion,
        int packetId,
        int rawSize,
        int compressedSize,
        boolean compressed) {
    public PacketView {
        if (rawSize < 0 || compressedSize < 0) {
            throw new IllegalArgumentException("packet sizes must be non-negative");
        }
    }
}

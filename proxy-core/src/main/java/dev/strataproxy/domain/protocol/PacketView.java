package dev.strataproxy.domain.protocol;

/**
 * Lightweight description of an observed packet frame.
 *
 * @param direction packet direction
 * @param state current protocol state
 * @param protocolVersion negotiated or observed Minecraft protocol version
 * @param packetId packet id
 * @param rawSize uncompressed payload size in bytes
 * @param compressedSize compressed frame size in bytes, or zero when not compressed
 * @param compressed whether the packet arrived in a compressed frame
 */
public record PacketView(
        PacketDirection direction,
        ProtocolState state,
        int protocolVersion,
        int packetId,
        int rawSize,
        int compressedSize,
        boolean compressed) {
    /**
     * Validates and normalizes record components.
     */
    public PacketView {
        if (rawSize < 0 || compressedSize < 0) {
            throw new IllegalArgumentException("packet sizes must be non-negative");
        }
    }
}

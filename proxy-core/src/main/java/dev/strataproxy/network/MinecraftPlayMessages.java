package dev.strataproxy.network;

import dev.strataproxy.network.MinecraftCompressionCodec;
import dev.strataproxy.network.MinecraftVarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

final class MinecraftPlayMessages {
    private MinecraftPlayMessages() {
    }

    static Optional<ByteBuf> systemChatFrame(
            ByteBufAllocator allocator,
            int protocolVersion,
            String message,
            boolean compressed,
            int compressionThreshold) {
        return systemChatFrame(
                allocator,
                MinecraftProtocolProfile.forVersion(protocolVersion),
                message,
                compressed,
                compressionThreshold);
    }

    static Optional<ByteBuf> systemChatFrame(
            ByteBufAllocator allocator,
            MinecraftProtocolProfile profile,
            String message,
            boolean compressed,
            int compressionThreshold) {
        var resolvedProfile = profile == null
                ? MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_20_1)
                : profile;
        var packetId = resolvedProfile.clientboundChatPacketId();
        if (packetId.isEmpty() || message == null || message.isBlank()) {
            return Optional.empty();
        }
        var packet = allocator.buffer();
        try {
            MinecraftVarInts.write(packet, packetId.getAsInt());
            writeString(packet, "{\"text\":\"" + escapeJson(message) + "\"}");
            if (resolvedProfile.clientboundChatHasOverlayFlag()) {
                packet.writeBoolean(false);
            }
            if (compressed) {
                try (var codec = new MinecraftCompressionCodec()) {
                    return Optional.of(codec.encodeFrame(allocator, packet, compressionThreshold));
                }
            }
            return Optional.of(frame(allocator, packet));
        } finally {
            packet.release();
        }
    }

    private static ByteBuf frame(ByteBufAllocator allocator, ByteBuf packet) {
        var output = allocator.buffer(MinecraftVarInts.encodedSize(packet.readableBytes()) + packet.readableBytes());
        MinecraftVarInts.write(output, packet.readableBytes());
        output.writeBytes(packet, packet.readerIndex(), packet.readableBytes());
        return output;
    }

    private static void writeString(ByteBuf output, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        MinecraftVarInts.write(output, bytes.length);
        output.writeBytes(bytes);
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

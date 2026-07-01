package dev.strataproxy.network;

import dev.strataproxy.codec.minecraft.MinecraftCompressionCodec;
import dev.strataproxy.codec.minecraft.MinecraftVarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

final class MinecraftPlayMessages {
    private static final int PROTOCOL_1_20_1 = 763;
    private static final int CLIENTBOUND_SYSTEM_CHAT_1_20_1 = 0x64;

    private MinecraftPlayMessages() {
    }

    static Optional<ByteBuf> systemChatFrame(
            ByteBufAllocator allocator,
            int protocolVersion,
            String message,
            boolean compressed,
            int compressionThreshold) {
        var packetId = systemChatPacketId(protocolVersion);
        if (packetId < 0 || message == null || message.isBlank()) {
            return Optional.empty();
        }
        var packet = allocator.buffer();
        try {
            MinecraftVarInts.write(packet, packetId);
            writeString(packet, "{\"text\":\"" + escapeJson(message) + "\"}");
            packet.writeBoolean(false);
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

    private static int systemChatPacketId(int protocolVersion) {
        return protocolVersion == PROTOCOL_1_20_1 ? CLIENTBOUND_SYSTEM_CHAT_1_20_1 : -1;
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

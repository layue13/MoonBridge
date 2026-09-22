package dev.strataproxy.infrastructure.minecraft;

import dev.strataproxy.infrastructure.minecraft.codec.MinecraftVarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.nio.charset.StandardCharsets;

final class MinecraftLoginDisconnect {
    private static final int LOGIN_DISCONNECT_PACKET_ID = 0x00;

    private MinecraftLoginDisconnect() {
    }

    static ByteBuf frame(ByteBufAllocator allocator, String reason) {
        var payload = allocator.buffer();
        try {
            MinecraftVarInts.write(payload, LOGIN_DISCONNECT_PACKET_ID);
            writeString(payload, "{\"text\":\"" + escapeJson(reason == null || reason.isBlank() ? "Disconnected" : reason) + "\"}");

            var frame = allocator.buffer(MinecraftVarInts.encodedSize(payload.readableBytes()) + payload.readableBytes());
            MinecraftVarInts.write(frame, payload.readableBytes());
            frame.writeBytes(payload);
            return frame;
        } finally {
            payload.release();
        }
    }

    private static void writeString(ByteBuf output, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        MinecraftVarInts.write(output, bytes.length);
        output.writeBytes(bytes);
    }

    private static String escapeJson(String value) {
        var builder = new StringBuilder(value.length() + 8);
        for (var index = 0; index < value.length(); index++) {
            var current = value.charAt(index);
            switch (current) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (current < 0x20) {
                        builder.append("\\u").append(String.format("%04x", (int) current));
                    } else {
                        builder.append(current);
                    }
                }
            }
        }
        return builder.toString();
    }
}

package dev.strataproxy.core.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.util.Objects;

/** Protocol 5 LOGIN clientbound Disconnect (packet id 0x00). */
public final class MinecraftLoginDisconnect {
    private static final ObjectMapper JSON = new ObjectMapper();

    private MinecraftLoginDisconnect() { }

    public static ByteBuf encode(ByteBufAllocator allocator, String reason) {
        Objects.requireNonNull(allocator, "allocator");
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank() || reason.codePointCount(0, reason.length()) > 1024) {
            throw new IllegalArgumentException("reason must contain 1 to 1024 characters");
        }
        String component = JSON.createObjectNode().put("text", reason).toString();
        ByteBuf packet = allocator.buffer();
        try {
            ProtocolVarInt.write(packet, 0);
            ProtocolStrings.write(packet, component, 32767);
            return packet;
        } catch (RuntimeException failure) {
            packet.release();
            throw failure;
        }
    }
}

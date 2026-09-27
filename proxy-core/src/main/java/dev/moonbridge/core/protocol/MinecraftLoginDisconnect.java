package dev.moonbridge.core.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import net.kyori.adventure.text.Component;

import java.util.Objects;

/** Protocol 5 LOGIN clientbound Disconnect (packet id 0x00). */
public final class MinecraftLoginDisconnect {

    private MinecraftLoginDisconnect() { }

    public static ByteBuf encode(ByteBufAllocator allocator, String reason) {
        Objects.requireNonNull(allocator, "allocator");
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank() || reason.codePointCount(0, reason.length()) > 1024) {
            throw new IllegalArgumentException("reason must contain 1 to 1024 characters");
        }
        return encodeJson(allocator, MinecraftText.encodeReason(Component.text(reason)));
    }

    /** Encodes a LOGIN Disconnect payload from prevalidated component JSON. */
    public static ByteBuf encodeJson(ByteBufAllocator allocator, String componentJson) {
        Objects.requireNonNull(allocator, "allocator");
        MinecraftText.validateEncoded(componentJson);
        ByteBuf packet = allocator.buffer();
        try {
            ProtocolVarInt.write(packet, 0);
            ProtocolStrings.write(packet, componentJson, 32767);
            return packet;
        } catch (RuntimeException failure) {
            packet.release();
            throw failure;
        }
    }
}

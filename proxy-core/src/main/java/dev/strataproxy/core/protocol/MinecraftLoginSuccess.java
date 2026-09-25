package dev.strataproxy.core.protocol;

import io.netty.buffer.ByteBuf;

import java.util.UUID;

/** Validated protocol 5 LOGIN success payload from a trusted backend. */
public record MinecraftLoginSuccess(UUID playerId, String username) {
    public static MinecraftLoginSuccess decode(ByteBuf packet) {
        ByteBuf input = packet.duplicate();
        if (ProtocolVarInt.read(input) != 2) throw new ProtocolException("expected Login Success");
        String uuidText = ProtocolStrings.read(input, 36);
        String username = ProtocolStrings.read(input, 16);
        if (input.isReadable()) throw new ProtocolException("trailing Login Success bytes");
        try {
            return new MinecraftLoginSuccess(UUID.fromString(uuidText), username);
        } catch (IllegalArgumentException malformed) {
            throw new ProtocolException("invalid Login Success UUID", malformed);
        }
    }
}

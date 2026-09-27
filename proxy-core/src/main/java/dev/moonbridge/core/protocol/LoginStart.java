package dev.moonbridge.core.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

/** Protocol 5 serverbound Login Start packet. */
public record LoginStart(String username) {
    public LoginStart {
        if (username == null || username.isBlank()) throw new IllegalArgumentException("username must not be blank");
    }

    public static LoginStart decode(ByteBuf packet, ProtocolProfile profile) {
        ByteBuf input = packet.duplicate();
        if (ProtocolVarInt.read(input) != 0) throw new ProtocolException("expected Login Start packet id 0");
        String name = ProtocolStrings.read(input, profile.maxLoginNameCharacters());
        if (input.isReadable()) throw new ProtocolException("trailing Login Start bytes");
        return new LoginStart(name);
    }

    public ByteBuf encode(ByteBufAllocator allocator, ProtocolProfile profile) {
        ByteBuf packet = allocator.buffer();
        ProtocolVarInt.write(packet, 0);
        ProtocolStrings.write(packet, username, profile.maxLoginNameCharacters());
        return packet;
    }
}

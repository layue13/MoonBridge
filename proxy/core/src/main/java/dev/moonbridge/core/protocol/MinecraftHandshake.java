package dev.moonbridge.core.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

/** The first serverbound packet, including its packet id. */
public record MinecraftHandshake(int protocolVersion, String serverAddress, int serverPort, NextState nextState) {
    public enum NextState { STATUS(1), LOGIN(2); private final int id; NextState(int id) { this.id = id; } }

    public MinecraftHandshake {
        if (serverAddress == null || serverAddress.isBlank()) throw new IllegalArgumentException("serverAddress must not be blank");
        if (serverPort < 0 || serverPort > 65535) throw new IllegalArgumentException("serverPort must fit unsigned short");
        if (nextState == null) throw new IllegalArgumentException("nextState is required");
    }

    public static MinecraftHandshake decode(ByteBuf packet, ProtocolProfile profile) {
        ByteBuf input = packet.duplicate();
        if (ProtocolVarInt.read(input) != 0) throw new ProtocolException("expected Handshake packet id 0");
        int version = ProtocolVarInt.read(input);
        String address = ProtocolStrings.read(input, profile.maxHandshakeHostCharacters());
        if (input.readableBytes() < 2) throw new ProtocolException("truncated handshake port");
        int port = input.readUnsignedShort();
        int stateId = ProtocolVarInt.read(input);
        NextState state = switch (stateId) { case 1 -> NextState.STATUS; case 2 -> NextState.LOGIN; default -> throw new ProtocolException("unsupported handshake next state: " + stateId); };
        if (input.isReadable()) throw new ProtocolException("trailing handshake bytes");
        return new MinecraftHandshake(version, address, port, state);
    }

    public ByteBuf encode(ByteBufAllocator allocator, ProtocolProfile profile) {
        return ByteBufs.fill(allocator.buffer(), packet -> {
            ProtocolVarInt.write(packet, 0);
            ProtocolVarInt.write(packet, protocolVersion);
            ProtocolStrings.write(packet, serverAddress, profile.maxHandshakeHostCharacters());
            packet.writeShort(serverPort);
            ProtocolVarInt.write(packet, nextState.id);
        });
    }
}

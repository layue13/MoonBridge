package dev.strataproxy.core.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.util.Optional;
import java.util.OptionalInt;

/** The few protocol 5 PLAY packets a backend switch must understand or synthesize. */
public final class Minecraft1710PlayPackets {
    public static final int JOIN_GAME = 0x01;
    public static final int RESPAWN = 0x07;
    public static final int CLIENT_CUSTOM_PAYLOAD = 0x17;
    public static final int SERVER_CUSTOM_PAYLOAD = 0x3F;
    public static final int SERVER_DISCONNECT = 0x40;

    private Minecraft1710PlayPackets() { }

    public record JoinGame(int entityId, int gameMode, int dimension, int difficulty, String levelType) { }

    public record ForgeServerHello(int protocolVersion, int dimensionOverride) { }
    public record ForgeHandshake(int discriminator, OptionalInt phase, Optional<ForgeServerHello> hello) { }

    public static Optional<JoinGame> joinGame(ByteBuf packet) {
        ByteBuf input = packet.duplicate();
        if (ProtocolVarInt.read(input) != JOIN_GAME) return Optional.empty();
        if (input.readableBytes() < 8) throw new ProtocolException("truncated Join Game");
        int entityId = input.readInt();
        int gameMode = input.readUnsignedByte();
        int dimension = input.readByte();
        int difficulty = input.readUnsignedByte();
        input.readUnsignedByte(); // Maximum players does not affect the local world transition.
        String levelType = ProtocolStrings.read(input, 16);
        return Optional.of(new JoinGame(entityId, gameMode, dimension, difficulty, levelType));
    }

    /** Forge 1.7.10 S3F uses a VarShort payload length. Vanilla-sized messages use the same two bytes. */
    public static Optional<ForgeServerHello> forgeServerHello(ByteBuf packet) {
        return forgeHandshake(packet, true).flatMap(ForgeHandshake::hello);
    }

    public static Optional<ForgeHandshake> forgeHandshake(ByteBuf packet, boolean clientbound) {
        ByteBuf input = packet.duplicate();
        if (ProtocolVarInt.read(input) != (clientbound ? SERVER_CUSTOM_PAYLOAD : CLIENT_CUSTOM_PAYLOAD)) {
            return Optional.empty();
        }
        String channel = ProtocolStrings.read(input, 20);
        if (!"FML|HS".equals(channel)) return Optional.empty();
        int length = clientbound ? readVarShort(input) : input.readUnsignedShort();
        if (length < 1 || length != input.readableBytes()) throw new ProtocolException("invalid FML handshake payload");
        int discriminator = input.readUnsignedByte();
        OptionalInt phase = OptionalInt.empty();
        Optional<ForgeServerHello> hello = Optional.empty();
        if (discriminator == 0 && clientbound) {
            if (!input.isReadable()) throw new ProtocolException("missing Forge ServerHello version");
            int version = input.readUnsignedByte();
            if (version > 1 && input.readableBytes() != 4) {
                throw new ProtocolException("invalid Forge ServerHello dimension");
            }
            hello = Optional.of(new ForgeServerHello(version, version > 1 ? input.readInt() : 0));
        } else if (discriminator == 0xFF) {
            if (!input.isReadable()) throw new ProtocolException("missing Forge handshake phase");
            phase = OptionalInt.of(input.readUnsignedByte());
        }
        return Optional.of(new ForgeHandshake(discriminator, phase, hello));
    }

    /** Always changes dimension before the target; the client's current dimension is not observed on the relay path. */
    public static ByteBuf respawnSequence(ByteBufAllocator allocator, JoinGame target, int targetDimension) {
        ByteBuf output = allocator.buffer();
        try {
            writeRespawn(output, allocator, target, targetDimension >= 0 ? -1 : 0);
            writeRespawn(output, allocator, target, targetDimension);
            return output;
        } catch (RuntimeException failure) {
            output.release();
            throw failure;
        }
    }

    public static ByteBuf forgeReset(ByteBufAllocator allocator) {
        ByteBuf packet = allocator.buffer();
        try {
            ProtocolVarInt.write(packet, SERVER_CUSTOM_PAYLOAD);
            ProtocolStrings.write(packet, "FML|HS", 20);
            packet.writeShort(1); // Forge VarShort encoding of a one-byte payload.
            packet.writeByte(0xFE);
            return frame(allocator, packet);
        } finally {
            packet.release();
        }
    }

    private static void writeRespawn(ByteBuf output, ByteBufAllocator allocator, JoinGame target, int dimension) {
        ByteBuf packet = allocator.buffer();
        try {
            ProtocolVarInt.write(packet, RESPAWN);
            packet.writeInt(dimension);
            packet.writeByte(target.difficulty());
            packet.writeByte(target.gameMode() & 0x07); // Respawn has no hardcore flag.
            ProtocolStrings.write(packet, target.levelType(), 16);
            ByteBuf framed = frame(allocator, packet);
            try {
                output.writeBytes(framed);
            } finally {
                framed.release();
            }
        } finally {
            packet.release();
        }
    }

    public static ByteBuf frame(ByteBufAllocator allocator, ByteBuf packet) {
        ByteBuf output = allocator.buffer(5 + packet.readableBytes());
        ProtocolVarInt.write(output, packet.readableBytes());
        output.writeBytes(packet, packet.readerIndex(), packet.readableBytes());
        return output;
    }

    private static int readVarShort(ByteBuf input) {
        if (input.readableBytes() < 2) throw new ProtocolException("truncated VarShort");
        int low = input.readUnsignedShort();
        if ((low & 0x8000) == 0) return low;
        if (!input.isReadable()) throw new ProtocolException("truncated VarShort extension");
        return ((input.readUnsignedByte() & 0xFF) << 15) | (low & 0x7FFF);
    }
}

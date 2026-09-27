package dev.moonbridge.core.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import net.kyori.adventure.text.Component;

import java.util.Optional;
import java.util.OptionalInt;

/** The few protocol 5 PLAY packets a backend switch must understand or synthesize. */
public final class Minecraft1710PlayPackets {
    public static final int CLIENT_CHAT = 0x01;
    public static final int SERVER_CHAT = 0x02;
    public static final int JOIN_GAME = 0x01;
    public static final int RESPAWN = 0x07;
    public static final int CLIENT_CUSTOM_PAYLOAD = 0x17;
    public static final int SERVER_CUSTOM_PAYLOAD = 0x3F;
    public static final int SERVER_DISCONNECT = 0x40;

    private Minecraft1710PlayPackets() { }

    /** Reads a complete client PLAY frame. Non-chat frames are left untouched. */
    public static Optional<String> playerChat(ByteBuf frame) {
        // The frame decoder already verified the prefix. Peek at the one-byte chat ID
        // before creating a duplicate so ordinary gameplay packets stay allocation-free.
        int offset = frame.readerIndex();
        for (int index = 0; index < 3; index++) {
            int current = frame.getUnsignedByte(offset++);
            if ((current & 0x80) == 0) {
                if (frame.getUnsignedByte(offset) != CLIENT_CHAT) return Optional.empty();
                break;
            }
        }
        ByteBuf input = frame.duplicate();
        int length = ProtocolVarInt.read(input);
        if (length < 1 || length != input.readableBytes()) throw new ProtocolException("invalid PLAY frame length");
        if (ProtocolVarInt.read(input) != CLIENT_CHAT) return Optional.empty();
        String message = ProtocolStrings.read(input, 100);
        if (input.isReadable()) throw new ProtocolException("trailing client chat bytes");
        return Optional.of(message);
    }

    /** Encodes a plain-text proxy reply as a protocol 5 clientbound PLAY chat frame. */
    public static ByteBuf chatReply(ByteBufAllocator allocator, String message) {
        validateText(message, true);
        return chatReplyEncoded(allocator, MinecraftText.encode(Component.text(message)));
    }

    /** Encodes a protocol 5 clientbound PLAY chat frame from prevalidated component JSON. */
    public static ByteBuf chatReplyEncoded(ByteBufAllocator allocator, String componentJson) {
        MinecraftText.validateEncoded(componentJson);
        if (allocator == null) throw new NullPointerException("allocator");
        ByteBuf packet = allocator.buffer();
        try {
            ProtocolVarInt.write(packet, SERVER_CHAT);
            ProtocolStrings.write(packet, componentJson, 32767);
            return frame(allocator, packet);
        } finally {
            packet.release();
        }
    }

    /** Encodes an unframed protocol 5 PLAY Disconnect payload. */
    public static ByteBuf disconnect(ByteBufAllocator allocator, String reason) {
        validateText(reason, false);
        return disconnectEncoded(allocator, MinecraftText.encodeReason(Component.text(reason)));
    }

    /** Encodes an unframed protocol 5 PLAY Disconnect payload from prevalidated component JSON. */
    public static ByteBuf disconnectEncoded(ByteBufAllocator allocator, String componentJson) {
        MinecraftText.validateEncoded(componentJson);
        if (allocator == null) throw new NullPointerException("allocator");
        ByteBuf packet = allocator.buffer();
        try {
            ProtocolVarInt.write(packet, SERVER_DISCONNECT);
            ProtocolStrings.write(packet, componentJson, 32767);
            return packet;
        } catch (RuntimeException failure) {
            packet.release();
            throw failure;
        }
    }

    private static void validateText(String text, boolean allowEmpty) {
        if (text == null || (!allowEmpty && text.isBlank())
                || text.codePointCount(0, text.length()) > 1024) {
            throw new IllegalArgumentException(allowEmpty
                    ? "chat reply must contain at most 1024 Unicode code points"
                    : "disconnect reason must contain 1 to 1024 Unicode code points");
        }
    }

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

    /** Control channels needed while a replacement Forge backend negotiates its PLAY state. */
    public static boolean forgeControlPayload(ByteBuf packet) {
        ByteBuf input = packet.duplicate();
        if (ProtocolVarInt.read(input) != CLIENT_CUSTOM_PAYLOAD) return false;
        String channel = ProtocolStrings.read(input, 20);
        return "FML|HS".equals(channel) || "REGISTER".equals(channel)
                || "UNREGISTER".equals(channel);
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

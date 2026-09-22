package dev.strataproxy.network;

import dev.strataproxy.network.MinecraftVarInts;
import dev.strataproxy.network.MinecraftCompressionCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;

final class MinecraftLegacyTransferPackets {
    private MinecraftLegacyTransferPackets() {
    }

    static ByteBuf respawnSequenceFromJoinGame(
            ByteBufAllocator allocator,
            ByteBuf joinGamePacket,
            MinecraftProtocolProfile profile,
            boolean legacyForgeClient) {
        if (profile.clientboundPlayRespawnPacketId().isEmpty()) {
            throw new IllegalArgumentException("profile does not define a respawn packet");
        }
        var template = respawnTemplateFromJoinGame(joinGamePacket, profile, legacyForgeClient);
        var output = allocator.buffer();
        writeRespawnFrame(allocator, output, profile.clientboundPlayRespawnPacketId().getAsInt(),
                template.dimension() >= 0 ? -1 : 0,
                template.difficulty(),
                template.gameMode(),
                template.levelType());
        writeRespawnFrame(allocator, output, profile.clientboundPlayRespawnPacketId().getAsInt(),
                template.dimension(),
                template.difficulty(),
                template.gameMode(),
                template.levelType());
        return output;
    }

    static ByteBuf compressedRespawnSequenceFromJoinGame(
            ByteBufAllocator allocator,
            ByteBuf joinGamePacket,
            MinecraftProtocolProfile profile,
            MinecraftCompressionCodec codec,
            int threshold,
            boolean legacyForgeClient) {
        if (profile.clientboundPlayRespawnPacketId().isEmpty()) {
            throw new IllegalArgumentException("profile does not define a respawn packet");
        }
        if (codec == null) {
            throw new IllegalArgumentException("codec is required");
        }
        var template = respawnTemplateFromJoinGame(joinGamePacket, profile, legacyForgeClient);
        var output = allocator.buffer();
        writeCompressedRespawnFrame(allocator, output, codec, threshold, profile.clientboundPlayRespawnPacketId().getAsInt(),
                template.dimension() >= 0 ? -1 : 0,
                template.difficulty(),
                template.gameMode(),
                template.levelType());
        writeCompressedRespawnFrame(allocator, output, codec, threshold, profile.clientboundPlayRespawnPacketId().getAsInt(),
                template.dimension(),
                template.difficulty(),
                template.gameMode(),
                template.levelType());
        return output;
    }

    private static RespawnTemplate respawnTemplateFromJoinGame(
            ByteBuf joinGamePacket,
            MinecraftProtocolProfile profile,
            boolean legacyForgeClient) {
        var view = joinGamePacket.retainedDuplicate();
        try {
            var packetId = MinecraftProtocolCodec.readVarInt(view);
            if (profile.clientboundPlayLoginPacketId().isEmpty()
                    || packetId != profile.clientboundPlayLoginPacketId().getAsInt()) {
                throw new IllegalArgumentException("expected legacy Join Game packet, got " + packetId);
            }
            view.readInt();
            var gameMode = view.readUnsignedByte();
            var dimension = switch (profile.joinGameDimensionLayout(legacyForgeClient)) {
                case BYTE -> view.readByte();
                case INT -> view.readInt();
                case NONE -> throw new IllegalArgumentException("profile does not define a Join Game dimension layout");
            };
            var difficulty = view.readUnsignedByte();
            view.readUnsignedByte();
            var levelType = MinecraftProtocolCodec.readString(view, 16);
            return new RespawnTemplate(dimension, difficulty, gameMode, levelType);
        } finally {
            view.release();
        }
    }

    static ByteBuf forgeHandshakeResetFrame(ByteBufAllocator allocator, MinecraftProtocolProfile profile) {
        if (profile.clientboundCustomPayloadPacketId().isEmpty()) {
            throw new IllegalArgumentException("profile does not define a clientbound custom payload packet");
        }
        var packet = allocator.buffer();
        try {
            MinecraftVarInts.write(packet, profile.clientboundCustomPayloadPacketId().getAsInt());
            writeString(packet, "FML|HS");
            MinecraftCustomPayloadBodyCodec.writeLength(packet, profile.clientboundCustomPayloadLengthFormat(), 2);
            packet.writeByte(0xFE);
            packet.writeByte(0);
            var frame = allocator.buffer(MinecraftVarInts.encodedSize(packet.readableBytes()) + packet.readableBytes());
            MinecraftVarInts.write(frame, packet.readableBytes());
            frame.writeBytes(packet, packet.readerIndex(), packet.readableBytes());
            return frame;
        } finally {
            packet.release();
        }
    }

    static ByteBuf compressFrameBatch(
            ByteBufAllocator allocator,
            ByteBuf frames,
            MinecraftCompressionCodec codec,
            int threshold,
            int maxFrameBytes) {
        if (frames == null || !frames.isReadable()) {
            return Unpooled.EMPTY_BUFFER;
        }
        if (codec == null) {
            throw new IllegalArgumentException("codec is required");
        }
        var output = allocator.buffer(frames.readableBytes());
        try {
            var source = frames.retainedDuplicate();
            try {
                while (source.isReadable()) {
                    var probe = MinecraftVarInts.probe(source);
                    if (!probe.complete()) {
                        throw new IllegalArgumentException("truncated frame length");
                    }
                    if (probe.value() < 0 || probe.value() > maxFrameBytes) {
                        throw new IllegalArgumentException("frame exceeds maximum size");
                    }
                    var totalBytes = probe.bytes() + probe.value();
                    if (source.readableBytes() < totalBytes) {
                        throw new IllegalArgumentException("truncated frame");
                    }
                    source.skipBytes(probe.bytes());
                    var packet = source.readRetainedSlice(probe.value());
                    try {
                        var compressed = codec.encodeFrame(allocator, packet, threshold);
                        try {
                            output.writeBytes(compressed, compressed.readerIndex(), compressed.readableBytes());
                        } finally {
                            compressed.release();
                        }
                    } finally {
                        packet.release();
                    }
                }
            } finally {
                source.release();
            }
            return output;
        } catch (RuntimeException exception) {
            output.release();
            throw exception;
        }
    }

    private static void writeRespawnFrame(
            ByteBufAllocator allocator,
            ByteBuf output,
            int packetId,
            int dimension,
            int difficulty,
            int gameMode,
            String levelType) {
        var packet = allocator.buffer();
        try {
            MinecraftVarInts.write(packet, packetId);
            packet.writeInt(dimension);
            packet.writeByte(difficulty);
            packet.writeByte(gameMode);
            writeString(packet, levelType == null || levelType.isBlank() ? "default" : levelType);
            MinecraftVarInts.write(output, packet.readableBytes());
            output.writeBytes(packet, packet.readerIndex(), packet.readableBytes());
        } finally {
            packet.release();
        }
    }

    private static void writeCompressedRespawnFrame(
            ByteBufAllocator allocator,
            ByteBuf output,
            MinecraftCompressionCodec codec,
            int threshold,
            int packetId,
            int dimension,
            int difficulty,
            int gameMode,
            String levelType) {
        var packet = respawnPacket(allocator, packetId, dimension, difficulty, gameMode, levelType);
        try {
            var frame = codec.encodeFrame(allocator, packet, threshold);
            try {
                output.writeBytes(frame, frame.readerIndex(), frame.readableBytes());
            } finally {
                frame.release();
            }
        } finally {
            packet.release();
        }
    }

    private static ByteBuf respawnPacket(
            ByteBufAllocator allocator,
            int packetId,
            int dimension,
            int difficulty,
            int gameMode,
            String levelType) {
        var packet = allocator.buffer();
        MinecraftVarInts.write(packet, packetId);
        packet.writeInt(dimension);
        packet.writeByte(difficulty);
        packet.writeByte(gameMode);
        writeString(packet, levelType == null || levelType.isBlank() ? "default" : levelType);
        return packet;
    }

    private static void writeString(ByteBuf output, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        MinecraftVarInts.write(output, bytes.length);
        output.writeBytes(bytes);
    }

    private record RespawnTemplate(int dimension, int difficulty, int gameMode, String levelType) {
    }
}

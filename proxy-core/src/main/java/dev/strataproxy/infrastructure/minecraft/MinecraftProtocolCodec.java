package dev.strataproxy.infrastructure.minecraft;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

final class MinecraftProtocolCodec {
    private static final int MAX_VAR_INT_BYTES = 5;

    private MinecraftProtocolCodec() {
    }

    static FrameProbe probeFrame(ByteBuf input, int maxFrameLength) {
        var readerIndex = input.readerIndex();
        var readable = input.readableBytes();
        var value = 0;
        var position = 0;

        while (position < MAX_VAR_INT_BYTES) {
            if (position >= readable) {
                return FrameProbe.incomplete();
            }
            var current = input.getByte(readerIndex + position) & 0xFF;
            value |= (current & 0x7F) << (position * 7);
            position++;
            if ((current & 0x80) == 0) {
                if (value < 0 || value > maxFrameLength) {
                    throw new IllegalArgumentException("minecraft frame length out of bounds: " + value);
                }
                var totalLength = position + value;
                return readable >= totalLength
                        ? new FrameProbe(true, position, value, totalLength)
                        : FrameProbe.incomplete();
            }
        }
        throw new IllegalArgumentException("malformed VarInt frame length");
    }

    static MinecraftHandshake readHandshake(ByteBuf fullFrame, FrameProbe probe) {
        var payload = fullFrame.retainedDuplicate();
        try {
            payload.skipBytes(probe.varIntBytes());
            var packetId = readVarInt(payload);
            if (packetId != 0) {
                throw new IllegalArgumentException("first packet is not a handshake: " + packetId);
            }
            var protocolVersion = readVarInt(payload);
            var host = readString(payload, 255);
            var port = payload.readUnsignedShort();
            var nextState = readVarInt(payload);
            return new MinecraftHandshake(protocolVersion, host, port, nextState);
        } finally {
            payload.release();
        }
    }

    static int readVarInt(ByteBuf input) {
        var value = 0;
        var position = 0;
        while (position < MAX_VAR_INT_BYTES) {
            if (!input.isReadable()) {
                throw new IllegalArgumentException("truncated VarInt");
            }
            var current = input.readByte() & 0xFF;
            value |= (current & 0x7F) << (position * 7);
            position++;
            if ((current & 0x80) == 0) {
                return value;
            }
        }
        throw new IllegalArgumentException("malformed VarInt");
    }

    static String readString(ByteBuf input, int maxCharacters) {
        var length = readVarInt(input);
        if (length < 0 || length > maxCharacters * 4) {
            throw new IllegalArgumentException("string byte length out of bounds: " + length);
        }
        if (input.readableBytes() < length) {
            throw new IllegalArgumentException("truncated string");
        }
        var value = input.toString(input.readerIndex(), length, StandardCharsets.UTF_8);
        input.skipBytes(length);
        if (value.length() > maxCharacters) {
            throw new IllegalArgumentException("string character length out of bounds: " + value.length());
        }
        return value;
    }

    record FrameProbe(boolean complete, int varIntBytes, int payloadBytes, int totalBytes) {
        static FrameProbe incomplete() {
            return new FrameProbe(false, 0, 0, 0);
        }
    }
}

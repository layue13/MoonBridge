package dev.strataproxy.infrastructure.minecraft.codec;

import io.netty.buffer.ByteBuf;

/**
 * Utilities for Minecraft's 32-bit VarInt encoding.
 */
public final class MinecraftVarInts {
    private static final int SEGMENT_BITS = 0x7F;
    private static final int CONTINUE_BIT = 0x80;
    private static final int MAX_VARINT_BYTES = 5;

    private MinecraftVarInts() {
    }

    /**
     * Reads a VarInt and advances the reader index.
     *
     * @param input source buffer
     * @return decoded integer
     * @throws MinecraftCodecException when the value is truncated or longer than five bytes
     */
    public static int read(ByteBuf input) {
        var value = 0;
        var position = 0;
        while (position < MAX_VARINT_BYTES) {
            if (!input.isReadable()) {
                throw new MinecraftCodecException("truncated VarInt");
            }
            var current = input.readUnsignedByte();
            value |= (current & SEGMENT_BITS) << (position * 7);
            if ((current & CONTINUE_BIT) == 0) {
                return value;
            }
            position++;
        }
        throw new MinecraftCodecException("VarInt is too long");
    }

    /**
     * Peeks at a VarInt without advancing the reader index.
     *
     * @param input source buffer
     * @return probe result describing whether a complete VarInt is available
     */
    public static VarIntProbe probe(ByteBuf input) {
        var value = 0;
        var position = 0;
        var readerIndex = input.readerIndex();
        var readable = input.readableBytes();
        while (position < MAX_VARINT_BYTES) {
            if (position >= readable) {
                return VarIntProbe.incomplete();
            }
            var current = input.getUnsignedByte(readerIndex + position);
            value |= (current & SEGMENT_BITS) << (position * 7);
            if ((current & CONTINUE_BIT) == 0) {
                return new VarIntProbe(true, value, position + 1);
            }
            position++;
        }
        throw new MinecraftCodecException("VarInt is too long");
    }

    /**
     * Writes a VarInt.
     *
     * @param output destination buffer
     * @param value value to encode
     */
    public static void write(ByteBuf output, int value) {
        while ((value & ~SEGMENT_BITS) != 0) {
            output.writeByte((value & SEGMENT_BITS) | CONTINUE_BIT);
            value >>>= 7;
        }
        output.writeByte(value);
    }

    /**
     * Computes the encoded byte length of a VarInt value.
     *
     * @param value value to encode
     * @return number of bytes required
     */
    public static int encodedSize(int value) {
        var bytes = 1;
        while ((value & ~SEGMENT_BITS) != 0) {
            bytes++;
            value >>>= 7;
        }
        return bytes;
    }

    /**
     * Non-consuming VarInt probe result.
     *
     * @param complete whether a full VarInt was available
     * @param value decoded value when complete
     * @param bytes number of bytes occupied by the VarInt when complete
     */
    public record VarIntProbe(boolean complete, int value, int bytes) {
        private static VarIntProbe incomplete() {
            return new VarIntProbe(false, 0, 0);
        }
    }
}

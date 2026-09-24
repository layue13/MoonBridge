package dev.strataproxy.core.protocol;

import io.netty.buffer.ByteBuf;

/** Minecraft's signed 32-bit, little-endian base-128 integer encoding. */
public final class ProtocolVarInt {
    private ProtocolVarInt() { }

    public static int read(ByteBuf input) {
        int value = 0;
        int count = 0;
        while (count < 5) {
            if (!input.isReadable()) throw new ProtocolException("truncated VarInt");
            int current = input.readUnsignedByte();
            if (count == 4 && (current & 0xF0) != 0) throw new ProtocolException("VarInt exceeds 32 bits");
            value |= (current & 0x7F) << (count * 7);
            count++;
            if ((current & 0x80) == 0) {
                if (encodedSize(value) != count) throw new ProtocolException("non-canonical VarInt");
                return value;
            }
        }
        throw new ProtocolException("VarInt exceeds five bytes");
    }

    public static void write(ByteBuf output, int value) {
        do {
            int part = value & 0x7F;
            value >>>= 7;
            output.writeByte(value == 0 ? part : part | 0x80);
        } while (value != 0);
    }

    public static int encodedSize(int value) {
        int size = 1;
        while ((value & ~0x7F) != 0) {
            size++;
            value >>>= 7;
        }
        return size;
    }
}

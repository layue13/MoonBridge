package dev.strataproxy.infrastructure.minecraft;

import io.netty.buffer.ByteBuf;

final class MinecraftCustomPayloadBodyCodec {
    private MinecraftCustomPayloadBodyCodec() {
    }

    static ByteBuf readBody(
            ByteBuf input,
            MinecraftProtocolProfile.CustomPayloadLengthFormat format) {
        return switch (format) {
            case REMAINING_BYTES -> input.readSlice(input.readableBytes());
            case UNSIGNED_SHORT -> readUnsignedShortBody(input);
            case VARSHORT -> readVarShortBody(input);
        };
    }

    static void writeLength(
            ByteBuf output,
            MinecraftProtocolProfile.CustomPayloadLengthFormat format,
            int length) {
        if (length < 0) {
            throw new IllegalArgumentException("custom payload length must be non-negative");
        }
        switch (format) {
            case REMAINING_BYTES -> {
            }
            case UNSIGNED_SHORT -> {
                if (length > 0xFFFF) {
                    throw new IllegalArgumentException("custom payload too large for unsigned-short length");
                }
                output.writeShort(length);
            }
            case VARSHORT -> writeVarShort(output, length);
        }
    }

    private static ByteBuf readUnsignedShortBody(ByteBuf input) {
        if (input.readableBytes() < Short.BYTES) {
            throw new IllegalArgumentException("truncated custom payload length");
        }
        var length = input.readUnsignedShort();
        if (input.readableBytes() < length) {
            throw new IllegalArgumentException("truncated custom payload body");
        }
        return input.readSlice(length);
    }

    private static ByteBuf readVarShortBody(ByteBuf input) {
        var length = readVarShort(input);
        if (input.readableBytes() < length) {
            throw new IllegalArgumentException("truncated custom payload body");
        }
        return input.readSlice(length);
    }

    private static int readVarShort(ByteBuf input) {
        if (input.readableBytes() < Short.BYTES) {
            throw new IllegalArgumentException("truncated custom payload length");
        }
        var low = input.readUnsignedShort();
        var length = low & 0x7FFF;
        if ((low & 0x8000) != 0) {
            if (!input.isReadable()) {
                throw new IllegalArgumentException("truncated custom payload varshort extension");
            }
            length |= input.readUnsignedByte() << 15;
        }
        return length;
    }

    private static void writeVarShort(ByteBuf output, int value) {
        if (value > 0x7FFFFF) {
            throw new IllegalArgumentException("custom payload too large for varshort length");
        }
        var low = value & 0x7FFF;
        var high = (value & 0x7F8000) >>> 15;
        if (high != 0) {
            low |= 0x8000;
        }
        output.writeShort(low);
        if (high != 0) {
            output.writeByte(high);
        }
    }
}

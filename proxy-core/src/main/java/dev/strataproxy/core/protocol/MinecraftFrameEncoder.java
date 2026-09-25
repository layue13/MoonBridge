package dev.strataproxy.core.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

/** Writes one length-prefixed frame without changing or taking ownership of its payload. */
public final class MinecraftFrameEncoder {
    private MinecraftFrameEncoder() { }

    public static ByteBuf encode(ByteBufAllocator allocator, ByteBuf payload, ProtocolProfile profile) {
        int length = payload.readableBytes();
        if (length < 1 || length > profile.maxFrameBytes()) throw new ProtocolException("frame length out of bounds: " + length);
        ByteBuf output = allocator.buffer(ProtocolVarInt.encodedSize(length) + length);
        ProtocolVarInt.write(output, length);
        output.writeBytes(payload, payload.readerIndex(), length);
        return output;
    }
}

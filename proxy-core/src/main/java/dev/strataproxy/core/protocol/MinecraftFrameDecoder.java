package dev.strataproxy.core.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.DecoderException;
import java.util.List;

/** Splits Minecraft length-prefixed frames, returning reference-counted zero-copy slices. */
public final class MinecraftFrameDecoder extends ByteToMessageDecoder {
    private final ProtocolProfile profile;

    public MinecraftFrameDecoder(ProtocolProfile profile) { this.profile = profile; }

    @Override
    protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> output) {
        int start = input.readerIndex();
        int readable = input.readableBytes();
        int length = 0;
        int prefixBytes = 0;
        boolean completePrefix = false;
        while (prefixBytes < 5) {
            if (prefixBytes >= readable) return;
            int current = input.getUnsignedByte(start + prefixBytes);
            if (prefixBytes == 4 && (current & 0xF0) != 0) throw new DecoderException("frame length VarInt exceeds 32 bits");
            length |= (current & 0x7F) << (prefixBytes * 7);
            prefixBytes++;
            if ((current & 0x80) == 0) {
                completePrefix = true;
                break;
            }
        }
        if (!completePrefix) throw new DecoderException("frame length VarInt exceeds five bytes");
        if (ProtocolVarInt.encodedSize(length) != prefixBytes) throw new DecoderException("non-canonical frame length");
        if (length < 0 || length > profile.maxFrameBytes()) throw new DecoderException("frame length out of bounds: " + length);
        if (readable - prefixBytes < length) return;
        input.skipBytes(prefixBytes);
        output.add(input.readRetainedSlice(length));
    }
}

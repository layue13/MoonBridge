package dev.moonbridge.core.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.DecoderException;
import java.util.List;

/** Splits Minecraft length-prefixed frames, returning reference-counted zero-copy slices. */
public final class MinecraftFrameDecoder extends ByteToMessageDecoder {
    private final ProtocolProfile profile;
    private final boolean retainLengthPrefix;
    private int maxFrameBytes;

    public MinecraftFrameDecoder(ProtocolProfile profile) { this(profile, false); }

    /** Retained-prefix mode yields complete wire frames for a packet-aware relay. */
    public MinecraftFrameDecoder(ProtocolProfile profile, boolean retainLengthPrefix) {
        this(profile, retainLengthPrefix, profile.maxFrameBytes());
    }

    /** Starts with a smaller frame limit while the peer is still in the login phase. */
    public MinecraftFrameDecoder(ProtocolProfile profile, boolean retainLengthPrefix, int initialMaxFrameBytes) {
        if (initialMaxFrameBytes < 1 || initialMaxFrameBytes > profile.maxFrameBytes()) {
            throw new IllegalArgumentException("initial frame limit must fit the protocol profile");
        }
        this.profile = profile;
        this.retainLengthPrefix = retainLengthPrefix;
        this.maxFrameBytes = initialMaxFrameBytes;
    }

    /** Call on the channel event loop after login success, before accepting PLAY traffic. */
    public void allowPlayFrames() { maxFrameBytes = profile.maxFrameBytes(); }

    /** Call on the channel event loop after reads are paused before moving a session to another backend. */
    public boolean hasPartialFrame() {
        return actualReadableBytes() > 0;
    }

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
        if (length < 1 || length > maxFrameBytes) throw new DecoderException("frame length out of bounds: " + length);
        if (readable - prefixBytes < length) return;
        if (retainLengthPrefix) output.add(input.readRetainedSlice(prefixBytes + length));
        else {
            input.skipBytes(prefixBytes);
            output.add(input.readRetainedSlice(length));
        }
    }
}

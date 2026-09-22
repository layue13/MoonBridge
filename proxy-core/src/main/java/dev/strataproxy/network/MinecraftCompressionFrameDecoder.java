package dev.strataproxy.network;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * Netty decoder that converts Minecraft compressed frames into uncompressed packet payload buffers.
 */
public final class MinecraftCompressionFrameDecoder extends ByteToMessageDecoder {
    private final int threshold;
    private final int maxFrameBytes;
    private final int maxUncompressedBytes;
    private final MinecraftCompressionCodec codec;

    /**
 * Documents this public API element.
 *
     * @param threshold negotiated compression threshold
     * @param maxFrameBytes maximum compressed frame envelope size
     * @param maxUncompressedBytes maximum decoded packet payload size
     */
    public MinecraftCompressionFrameDecoder(int threshold, int maxFrameBytes, int maxUncompressedBytes) {
        if (threshold < 0) {
            throw new IllegalArgumentException("threshold must be non-negative");
        }
        if (maxFrameBytes <= 0 || maxUncompressedBytes <= 0) {
            throw new IllegalArgumentException("frame limits must be positive");
        }
        this.threshold = threshold;
        this.maxFrameBytes = maxFrameBytes;
        this.maxUncompressedBytes = maxUncompressedBytes;
        this.codec = new MinecraftCompressionCodec();
    }

    @Override
    protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> output) {
        var probe = MinecraftVarInts.probe(input);
        if (!probe.complete()) {
            return;
        }
        var packetLength = probe.value();
        if (packetLength < 0 || packetLength > maxFrameBytes) {
            throw new MinecraftCodecException("compressed frame length exceeds maximum");
        }
        var totalBytes = probe.bytes() + packetLength;
        if (input.readableBytes() < totalBytes) {
            return;
        }
        var frame = input.readRetainedSlice(totalBytes);
        try {
            output.add(codec.decodeFrame(context.alloc(), frame, threshold, maxUncompressedBytes));
        } finally {
            frame.release();
        }
    }

    @Override
    protected void handlerRemoved0(ChannelHandlerContext context) {
        codec.close();
    }
}

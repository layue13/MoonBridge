package dev.strataproxy.infrastructure.minecraft.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;

import java.util.List;

/**
 * Netty encoder that wraps packet payload buffers in Minecraft compressed-frame envelopes.
 */
public final class MinecraftCompressionFrameEncoder extends MessageToMessageEncoder<ByteBuf> {
    private final int threshold;
    private final MinecraftCompressionCodec codec;

    /**
 * Documents this public API element.
 *
     * @param threshold negotiated compression threshold
     */
    public MinecraftCompressionFrameEncoder(int threshold) {
        if (threshold < 0) {
            throw new IllegalArgumentException("threshold must be non-negative");
        }
        this.threshold = threshold;
        this.codec = new MinecraftCompressionCodec();
    }

    @Override
    protected void encode(ChannelHandlerContext context, ByteBuf message, List<Object> output) {
        output.add(codec.encodeFrame(context.alloc(), message, threshold));
    }

    @Override
    /** Provides handler removed. */
    public void handlerRemoved(ChannelHandlerContext context) {
        codec.close();
    }
}

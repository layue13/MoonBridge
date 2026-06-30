package dev.strataproxy.codec.minecraft;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;

import java.util.List;

public final class MinecraftCompressionFrameEncoder extends MessageToMessageEncoder<ByteBuf> {
    private final int threshold;
    private final MinecraftCompressionCodec codec;

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
    public void handlerRemoved(ChannelHandlerContext context) {
        codec.close();
    }
}

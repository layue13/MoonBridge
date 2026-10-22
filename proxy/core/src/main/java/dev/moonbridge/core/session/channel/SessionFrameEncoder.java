package dev.moonbridge.core.session.channel;

import dev.moonbridge.core.protocol.MinecraftFrameEncoder;
import dev.moonbridge.core.protocol.ProtocolProfile;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;

import java.util.List;

public final class SessionFrameEncoder extends MessageToMessageEncoder<ByteBuf> {
    @Override protected void encode(ChannelHandlerContext ctx, ByteBuf message, List<Object> output) {
        output.add(MinecraftFrameEncoder.encode(ctx.alloc(), message, ProtocolProfile.minecraft1710()));
    }
}

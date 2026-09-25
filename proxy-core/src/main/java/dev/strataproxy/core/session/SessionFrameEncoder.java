package dev.strataproxy.core.session;

import dev.strataproxy.core.protocol.MinecraftFrameEncoder;
import dev.strataproxy.core.protocol.ProtocolProfile;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;

import java.util.List;

final class SessionFrameEncoder extends MessageToMessageEncoder<ByteBuf> {
    @Override protected void encode(ChannelHandlerContext ctx, ByteBuf message, List<Object> output) {
        output.add(MinecraftFrameEncoder.encode(ctx.alloc(), message, ProtocolProfile.minecraft1710()));
    }
}

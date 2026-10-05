package dev.moonbridge.core.session;

import dev.moonbridge.core.protocol.Minecraft1710PlayPackets;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

import java.util.function.Predicate;

/** Consumes only registered proxy commands; all other PLAY frames keep their wire bytes. */
final class PlayerCommandInterceptor extends FrameTransformHandler {
    private final Predicate<String> dispatch;

    PlayerCommandInterceptor(Predicate<String> dispatch, Runnable closeSession) {
        super(closeSession);
        this.dispatch = dispatch;
    }

    @Override protected ByteBuf transform(ChannelHandlerContext ctx, ByteBuf frame) {
        var chat = Minecraft1710PlayPackets.playerChat(frame);
        return chat.isPresent() && chat.get().startsWith("/") && dispatch.test(chat.get()) ? null : frame;
    }
}

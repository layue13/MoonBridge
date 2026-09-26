package dev.strataproxy.core.session;

import dev.strataproxy.core.protocol.Minecraft1710PlayPackets;
import dev.strataproxy.core.relay.RawRelay;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

import java.util.function.Predicate;

/** Consumes only registered proxy commands; all other PLAY frames keep their wire bytes. */
final class PlayerCommandInterceptor extends ChannelInboundHandlerAdapter {
    private final Predicate<String> dispatch;
    private final Runnable closeSession;

    PlayerCommandInterceptor(Predicate<String> dispatch, Runnable closeSession) {
        this.dispatch = dispatch;
        this.closeSession = closeSession;
    }

    @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (!(message instanceof ByteBuf frame)) {
            ReferenceCountUtil.release(message);
            closeSession.run();
            return;
        }
        ByteBuf outgoing = frame;
        try {
            var chat = Minecraft1710PlayPackets.playerChat(frame);
            if (chat.isPresent() && chat.get().startsWith("/") && dispatch.test(chat.get())) {
                outgoing = null;
                frame.release();
                RawRelay.continueAfterDrop(ctx.channel());
            } else {
                outgoing = null;
                ctx.fireChannelRead(frame);
            }
        } catch (RuntimeException malformed) {
            closeSession.run();
        } finally {
            if (outgoing != null) outgoing.release();
        }
    }
}

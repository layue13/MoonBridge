package dev.strataproxy.core.session;

import dev.strataproxy.core.protocol.ProtocolProfile;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

import java.util.ArrayDeque;

/** Holds complete client frames only while the session changes backend. */
final class TransferInboundBuffer extends ChannelInboundHandlerAdapter {
    private static final int MAX_BYTES = ProtocolProfile.minecraft1710().maxFrameBytes();
    private static final int MAX_MESSAGES = 1024;

    private final ArrayDeque<ByteBuf> frames = new ArrayDeque<>();
    private final Runnable closeSession;
    private ChannelHandlerContext context;
    private int bytes;
    private boolean draining;

    TransferInboundBuffer(Runnable closeSession) {
        this.closeSession = closeSession;
    }

    @Override public void handlerAdded(ChannelHandlerContext ctx) {
        context = ctx;
    }

    @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (draining) {
            ctx.fireChannelRead(message);
            return;
        }
        if (!(message instanceof ByteBuf frame)) {
            ReferenceCountUtil.release(message);
            closeSession.run();
            return;
        }
        int size = frame.readableBytes();
        if (frames.size() >= MAX_MESSAGES || size > MAX_BYTES - bytes) {
            frame.release();
            closeSession.run();
            return;
        }
        frames.addLast(frame);
        bytes += size;
    }

    /** Replays frames after this handler, preserving the already decoded frame boundary. */
    void drainAndRemove() {
        var ctx = context;
        if (ctx == null || !ctx.executor().inEventLoop()) {
            throw new IllegalStateException("transfer buffer must drain on its channel event loop");
        }
        draining = true;
        try {
            ByteBuf frame;
            while (ctx.channel().isActive() && (frame = frames.pollFirst()) != null) {
                bytes -= frame.readableBytes();
                ctx.fireChannelRead(frame);
            }
        } finally {
            releaseFrames();
            ctx.pipeline().remove(this);
        }
    }

    @Override public void handlerRemoved(ChannelHandlerContext ctx) {
        releaseFrames();
    }

    @Override public void channelInactive(ChannelHandlerContext ctx) {
        releaseFrames();
        ctx.fireChannelInactive();
    }

    private void releaseFrames() {
        ByteBuf frame;
        while ((frame = frames.pollFirst()) != null) frame.release();
        bytes = 0;
    }
}

package dev.moonbridge.core.session;

import dev.moonbridge.core.protocol.ProtocolProfile;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

import java.util.ArrayDeque;

/** Holds decoded frames on either side of the old session during backend cutover. */
final class TransferFrameBuffer extends ChannelInboundHandlerAdapter {
    // The decoder retains the three-byte length prefix on a maximum-sized protocol 5 frame.
    private static final int MAX_BYTES = ProtocolProfile.minecraft1710().maxFrameBytes() + 3;
    private static final int MAX_MESSAGES = 1024;

    private final ArrayDeque<ByteBuf> frames = new ArrayDeque<>();
    private final Runnable closeSession;
    private ChannelHandlerContext context;
    private int bytes;
    private boolean draining;
    private boolean reading;

    TransferFrameBuffer(Runnable closeSession) {
        this.closeSession = closeSession;
    }

    @Override public void handlerAdded(ChannelHandlerContext ctx) {
        context = ctx;
    }

    /** Keep reading bounded frames so a paused transfer also observes peer disconnects. */
    void readUntilRemoved() {
        ChannelHandlerContext ctx = context;
        if (ctx == null || !ctx.executor().inEventLoop()) {
            throw new IllegalStateException("transfer buffer must read on its channel event loop");
        }
        reading = true;
        if (!draining && ctx.channel().isActive()) ctx.read();
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

    @Override public void channelReadComplete(ChannelHandlerContext ctx) {
        if (reading && !draining && ctx.channel().isActive()) ctx.read();
        ctx.fireChannelReadComplete();
    }

    private void releaseFrames() {
        ByteBuf frame;
        while ((frame = frames.pollFirst()) != null) frame.release();
        bytes = 0;
    }
}

package dev.moonbridge.core.websocket;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.CoalescingBufferQueue;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

import java.nio.channels.ClosedChannelException;

/**
 * Carries the raw Minecraft TCP byte stream in binary WebSocket messages. Message boundaries carry no
 * meaning: the Minecraft frame decoder behind this handler reassembles frames from any chunking, and it
 * also requests the next read when a message yields no complete frame, so manual-read relays never stall.
 * Ping, pong and close frames are answered by Netty's protocol handler in front of this one.
 *
 * <p>Outbound, the bytes written before a flush become one binary message of at most {@link #MAX_MESSAGE_BYTES}.
 * The relay writes one Minecraft frame per write and flushes once per read batch, so a burst of small frames costs
 * one message header instead of one per frame, and a batch of at least 1 KiB is sent by Netty's encoder without
 * copying. Each write's promise completes when the message carrying it is written.
 */
public final class WebSocketFrameAdapter extends ChannelDuplexHandler {
    /** Far below the client mod's 16 MiB frame limit and typical CDN message limits. */
    static final int MAX_MESSAGE_BYTES = 64 * 1024;

    private CoalescingBufferQueue pending;

    @Override public void handlerAdded(ChannelHandlerContext ctx) {
        // Counting queued bytes as pending outbound keeps channel writability, and so relay backpressure, accurate.
        pending = new CoalescingBufferQueue(ctx.channel(), 4, true);
    }

    @Override public void handlerRemoved(ChannelHandlerContext ctx) {
        pending.releaseAndFailAll(ctx, new ClosedChannelException());
    }

    @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (!(message instanceof WebSocketFrame frame)) {
            ctx.fireChannelRead(message);
            return;
        }
        if (frame instanceof BinaryWebSocketFrame || frame instanceof ContinuationWebSocketFrame) {
            if (frame.content().isReadable()) {
                ctx.fireChannelRead(frame.content()); // Ownership moves with the payload.
                return;
            }
        } else if (frame instanceof TextWebSocketFrame) {
            frame.release();
            ctx.close();
            return;
        }
        frame.release();
    }

    @Override public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
        if (!(message instanceof ByteBuf bytes)) {
            ctx.write(message, promise);
            return;
        }
        pending.add(bytes, promise);
        if (pending.readableBytes() >= MAX_MESSAGE_BYTES) writePending(ctx);
    }

    @Override public void flush(ChannelHandlerContext ctx) {
        writePending(ctx);
        ctx.flush();
    }

    @Override public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
        // Unflushed writes fail on close, as they would in the channel's own outbound buffer.
        pending.releaseAndFailAll(ctx, new ClosedChannelException());
        ctx.close(promise);
    }

    @Override public void channelInactive(ChannelHandlerContext ctx) {
        pending.releaseAndFailAll(ctx, new ClosedChannelException());
        ctx.fireChannelInactive();
    }

    private void writePending(ChannelHandlerContext ctx) {
        while (!pending.isEmpty()) {
            ChannelPromise written = ctx.newPromise();
            ByteBuf payload = pending.remove(ctx.alloc(), MAX_MESSAGE_BYTES, written);
            ctx.write(new BinaryWebSocketFrame(payload), written);
        }
    }
}

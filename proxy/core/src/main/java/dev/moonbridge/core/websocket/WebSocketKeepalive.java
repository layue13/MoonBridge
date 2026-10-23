package dev.moonbridge.core.websocket;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Sends an empty WebSocket ping when the proxy has written nothing for a while, so a CDN or load balancer between
 * the player and the proxy sees traffic during quiet stretches (a long modded login, a placement wait, a stalled
 * backend) and does not cut the connection at its idle limit. The client's pong is dropped by Netty's protocol
 * handler.
 *
 * <p>A write only sets a flag; a check every half interval pings if the last half interval had no write. So the
 * proxy is never silent for longer than the interval, never pings after less than half of it, and the data path
 * pays nothing beyond the flag (an {@code IdleStateHandler}, which adds a listener to every write, measured
 * slower; see testing/benchmarks/results/2026-10-23-websocket-cdn.md). An unwritable connection is skipped: bytes
 * are already queued there.
 */
final class WebSocketKeepalive extends ChannelOutboundHandlerAdapter {
    private final long checkNanos;
    private boolean wrote;
    private ScheduledFuture<?> check;

    WebSocketKeepalive(Duration interval) {
        checkNanos = Math.max(1, interval.toNanos() / 2);
    }

    @Override public void handlerAdded(ChannelHandlerContext ctx) {
        check = ctx.executor().scheduleAtFixedRate(() -> check(ctx), checkNanos, checkNanos, TimeUnit.NANOSECONDS);
    }

    @Override public void handlerRemoved(ChannelHandlerContext ctx) {
        check.cancel(false);
    }

    @Override public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
        wrote = true;
        ctx.write(message, promise);
    }

    private void check(ChannelHandlerContext ctx) {
        if (!ctx.channel().isActive()) {
            check.cancel(false);
            return;
        }
        boolean idle = !wrote;
        wrote = false;
        if (idle && ctx.channel().isWritable()) {
            // Written from this context, so it bypasses write(); count it as this period's write by hand.
            ctx.writeAndFlush(new PingWebSocketFrame()); // A failed ping means a closing channel; nothing to do.
            wrote = true;
        }
    }
}

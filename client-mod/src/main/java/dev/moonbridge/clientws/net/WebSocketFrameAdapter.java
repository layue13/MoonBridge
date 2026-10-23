package dev.moonbridge.clientws.net;

import java.io.IOException;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

/**
 * Turns the WebSocket message stream back into the raw Minecraft byte stream.
 *
 * <p>
 * {@link ClientFrameDecoder} already passes binary and continuation payloads on as plain {@link ByteBuf}s, so the
 * vanilla splitter sees one concatenated byte stream regardless of how the server chunked it; they go through as-is
 * (ownership moves with them). Ping is answered with pong, close ends the connection, and a text frame is a protocol
 * error. Outbound, vanilla's {@link ByteBuf}s go straight to {@link ClientFrameEncoder}, which frames them.
 */
public final class WebSocketFrameAdapter extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof ByteBuf) {
            ByteBuf payload = (ByteBuf) msg;
            if (payload.isReadable()) {
                ctx.fireChannelRead(payload);
            } else {
                payload.release();
            }
        } else if (msg instanceof PingWebSocketFrame) {
            ctx.writeAndFlush(new PongWebSocketFrame(((PingWebSocketFrame) msg).content()));
        } else if (msg instanceof PongWebSocketFrame) {
            ((PongWebSocketFrame) msg).release();
        } else if (msg instanceof CloseWebSocketFrame) {
            ((CloseWebSocketFrame) msg).release();
            ctx.writeAndFlush(new CloseWebSocketFrame())
                .addListener(ChannelFutureListener.CLOSE);
        } else if (msg instanceof TextWebSocketFrame) {
            ((TextWebSocketFrame) msg).release();
            ctx.writeAndFlush(new CloseWebSocketFrame(1003, "text frames are not supported"))
                .addListener(ChannelFutureListener.CLOSE);
            ctx.fireExceptionCaught(new IOException("WebSocket protocol error: server sent a text frame"));
        } else {
            ctx.fireChannelRead(msg);
        }
    }
}

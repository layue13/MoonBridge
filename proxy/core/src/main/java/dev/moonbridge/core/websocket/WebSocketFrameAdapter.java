package dev.moonbridge.core.websocket;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

import java.util.List;

/**
 * Carries the raw Minecraft TCP byte stream in binary WebSocket messages. Message boundaries carry no
 * meaning: the Minecraft frame decoder behind this handler reassembles frames from any chunking, and it
 * also requests the next read when a message yields no complete frame, so manual-read relays never stall.
 * Ping, pong and close frames are answered by Netty's protocol handler in front of this one.
 */
public final class WebSocketFrameAdapter extends MessageToMessageCodec<WebSocketFrame, ByteBuf> {
    @Override protected void decode(ChannelHandlerContext ctx, WebSocketFrame frame, List<Object> out) {
        if (frame instanceof BinaryWebSocketFrame || frame instanceof ContinuationWebSocketFrame) {
            if (frame.content().isReadable()) out.add(frame.content().retain());
        } else if (frame instanceof TextWebSocketFrame) {
            ctx.close();
        }
    }

    @Override protected void encode(ChannelHandlerContext ctx, ByteBuf bytes, List<Object> out) {
        out.add(new BinaryWebSocketFrame(bytes.retain()));
    }
}

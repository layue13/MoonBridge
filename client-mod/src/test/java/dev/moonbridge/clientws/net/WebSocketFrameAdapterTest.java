package dev.moonbridge.clientws.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

/** Ownership checks: every consumed frame is released, forwarded payloads keep exactly one reference. */
class WebSocketFrameAdapterTest {

    @Test
    void binaryPayloadIsForwardedWithoutCopy() {
        EmbeddedChannel ch = new EmbeddedChannel(new WebSocketFrameAdapter());
        ByteBuf payload = Unpooled.buffer()
            .writeBytes(new byte[] { 1, 2, 3 });
        ch.writeInbound(new BinaryWebSocketFrame(payload));
        ByteBuf out = (ByteBuf) ch.readInbound();
        assertSame(payload, out);
        assertEquals(1, out.refCnt());
        out.release();
        ch.finish();
    }

    @Test
    void emptyAndControlFramesAreReleased() {
        EmbeddedChannel ch = new EmbeddedChannel(new WebSocketFrameAdapter());
        ByteBuf empty = Unpooled.buffer();
        ch.writeInbound(new BinaryWebSocketFrame(empty));
        assertNull(ch.readInbound());
        assertEquals(0, empty.refCnt());

        ByteBuf pongPayload = Unpooled.buffer()
            .writeByte(7);
        ch.writeInbound(new PongWebSocketFrame(pongPayload));
        assertEquals(0, pongPayload.refCnt());

        ByteBuf pingPayload = Unpooled.buffer()
            .writeByte(9);
        ch.writeInbound(new PingWebSocketFrame(pingPayload));
        PongWebSocketFrame pong = (PongWebSocketFrame) ch.readOutbound();
        assertSame(pingPayload, pong.content());
        pong.release();
        assertEquals(0, pingPayload.refCnt());
        ch.finish();
    }

    @Test
    void textFrameIsReleasedAndClosesWithError() {
        EmbeddedChannel ch = new EmbeddedChannel(new WebSocketFrameAdapter());
        ByteBuf text = Unpooled.copiedBuffer(new byte[] { 'h', 'i' });
        try {
            ch.writeInbound(new TextWebSocketFrame(text));
            ch.checkException();
            throw new AssertionError("expected exception");
        } catch (Exception expected) {
            assertTrue(
                expected.getMessage()
                    .contains("text frame"));
        }
        assertEquals(0, text.refCnt());
        CloseWebSocketFrame close = (CloseWebSocketFrame) ch.readOutbound();
        assertEquals(1003, close.statusCode());
        close.release();
        assertFalse(ch.isOpen());
    }

    @Test
    void closeFrameIsAnsweredAndCloses() {
        EmbeddedChannel ch = new EmbeddedChannel(new WebSocketFrameAdapter());
        CloseWebSocketFrame in = new CloseWebSocketFrame(1000, "bye");
        ch.writeInbound(in);
        assertEquals(0, in.refCnt());
        CloseWebSocketFrame reply = (CloseWebSocketFrame) ch.readOutbound();
        reply.release();
        assertFalse(ch.isOpen());
    }

    @Test
    void outboundBuffersBecomeBinaryFrames() {
        EmbeddedChannel ch = new EmbeddedChannel(new WebSocketFrameAdapter());
        ByteBuf data = Unpooled.buffer()
            .writeBytes(new byte[] { 4, 5 });
        ch.writeOutbound(data);
        BinaryWebSocketFrame frame = (BinaryWebSocketFrame) ch.readOutbound();
        assertSame(data, frame.content());
        frame.release();

        ByteBuf empty = Unpooled.buffer();
        ch.writeOutbound(empty);
        assertNull(ch.readOutbound());
        assertEquals(0, empty.refCnt());
        ch.finish();
    }
}

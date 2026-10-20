package dev.moonbridge.core.websocket;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

final class WebSocketFrameAdapterTest {
    @Test
    void binaryAndContinuationPayloadsPassThroughWithoutCopying() {
        var channel = new EmbeddedChannel(new WebSocketFrameAdapter());
        ByteBuf payload = Unpooled.buffer().writeBytes(new byte[]{1, 2, 3});
        var frame = new BinaryWebSocketFrame(false, 0, payload);
        channel.writeInbound(frame);
        ByteBuf forwarded = channel.readInbound();
        assertSame(payload, forwarded);
        assertEquals(1, forwarded.refCnt());
        forwarded.release();
        assertEquals(0, payload.refCnt());

        channel.writeInbound(new ContinuationWebSocketFrame(Unpooled.buffer().writeByte(4)));
        ByteBuf continued = channel.readInbound();
        assertEquals(4, continued.readByte());
        continued.release();
        assertFalse(channel.finish());
    }

    @Test
    void emptyMessagesProduceNothing() {
        var channel = new EmbeddedChannel(new WebSocketFrameAdapter());
        var empty = new BinaryWebSocketFrame(Unpooled.buffer(0));
        channel.writeInbound(empty);
        assertNull(channel.readInbound());
        assertEquals(0, empty.refCnt());
        assertFalse(channel.finish());
    }

    @Test
    void textFrameClosesTheConnection() {
        var channel = new EmbeddedChannel(new WebSocketFrameAdapter());
        var text = new TextWebSocketFrame("hello");
        channel.writeInbound(text);
        assertNull(channel.readInbound());
        assertEquals(0, text.refCnt());
        assertFalse(channel.isOpen());
    }

    @Test
    void outboundBytesBecomeOneBinaryMessage() {
        var channel = new EmbeddedChannel(new WebSocketFrameAdapter());
        ByteBuf bytes = Unpooled.buffer().writeBytes(new byte[]{9, 8});
        channel.writeOutbound(bytes);
        BinaryWebSocketFrame frame = channel.readOutbound();
        assertSame(bytes, frame.content());
        assertEquals(1, bytes.refCnt());
        frame.release();
        assertFalse(channel.finish());
    }
}

package dev.moonbridge.core.websocket;

import io.netty.buffer.Unpooled;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A 30-second interval: checks run every 15 seconds and ping after a check period without writes. */
final class WebSocketKeepaliveTest {
    private static EmbeddedChannel channel() {
        var channel = new EmbeddedChannel();
        channel.freezeTime();
        channel.pipeline().addLast(new WebSocketKeepalive(Duration.ofSeconds(30)));
        return channel;
    }

    private static void advance(EmbeddedChannel channel, long seconds) {
        channel.advanceTimeBy(seconds, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();
    }

    @Test
    void anIdleConnectionGetsAnEmptyPingEveryHalfInterval() {
        var channel = channel();
        advance(channel, 14);
        assertNull(channel.readOutbound());
        advance(channel, 1);
        WebSocketFrame ping = assertInstanceOf(PingWebSocketFrame.class, channel.readOutbound());
        assertEquals(0, ping.content().readableBytes());
        ping.release();
        // The ping itself is a write, so the next check skips and the one after pings again.
        advance(channel, 15);
        assertNull(channel.readOutbound());
        advance(channel, 15);
        assertInstanceOf(PingWebSocketFrame.class, channel.<WebSocketFrame>readOutbound()).release();
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void writesPostponeThePingSoSilenceNeverExceedsTheInterval() {
        var channel = channel();
        advance(channel, 10);
        channel.writeOutbound(new BinaryWebSocketFrame(Unpooled.buffer().writeByte(1)));
        channel.<WebSocketFrame>readOutbound().release();
        advance(channel, 20); // Checks at 15 (saw the write) and 30 (nothing since 15) -> ping 20 s after the write.
        assertInstanceOf(PingWebSocketFrame.class, channel.<WebSocketFrame>readOutbound()).release();
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void anUnwritableConnectionGetsNoPing() {
        var channel = channel();
        advance(channel, 15);
        channel.<WebSocketFrame>readOutbound().release();
        channel.config().setWriteBufferWaterMark(new WriteBufferWaterMark(1, 2));
        channel.write(new BinaryWebSocketFrame(Unpooled.buffer().writeBytes(new byte[64])));
        assertFalse(channel.isWritable());
        advance(channel, 30);
        channel.flushOutbound();
        assertInstanceOf(BinaryWebSocketFrame.class, channel.<WebSocketFrame>readOutbound()).release();
        assertNull(channel.readOutbound());
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void closingStopsTheChecks() {
        var channel = channel();
        channel.close();
        advance(channel, 60);
        assertNull(channel.readOutbound());
        assertFalse(channel.finishAndReleaseAll());
    }
}

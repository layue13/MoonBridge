package dev.moonbridge.core.session.play;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.DefaultByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The base class owns every release, so each answer a subclass can give is pinned here once. */
final class FrameTransformHandlerTest {
    private final AtomicInteger closed = new AtomicInteger();

    private EmbeddedChannel channel(BiFunction<ChannelHandlerContext, ByteBuf, ByteBuf> transform) {
        return new EmbeddedChannel(new FrameTransformHandler(closed::incrementAndGet) {
            @Override protected ByteBuf transform(ChannelHandlerContext ctx, ByteBuf frame) {
                return transform.apply(ctx, frame);
            }
        });
    }

    @Test
    void forwardsTheSameFrameWithoutTouchingItsReferenceCount() {
        var channel = channel((ctx, frame) -> frame);
        var frame = Unpooled.buffer().writeByte(1);
        channel.writeInbound(frame);
        ByteBuf out = channel.readInbound();
        assertSame(frame, out);
        assertEquals(1, out.refCnt());
        out.release();
        assertEquals(0, closed.get());
        channel.finishAndReleaseAll();
    }

    @Test
    void releasesTheOriginalWhenForwardingAReplacement() {
        var replacement = Unpooled.buffer().writeByte(2);
        var channel = channel((ctx, frame) -> replacement);
        var frame = Unpooled.buffer().writeByte(1);
        channel.writeInbound(frame);
        assertEquals(0, frame.refCnt(), "the borrowed frame must be released");
        ByteBuf out = channel.readInbound();
        assertSame(replacement, out);
        assertEquals(1, out.refCnt());
        out.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void releasesADroppedFrameAndForwardsNothing() {
        var channel = channel((ctx, frame) -> null);
        var frame = Unpooled.buffer().writeByte(1);
        channel.writeInbound(frame);
        assertEquals(0, frame.refCnt());
        assertNull(channel.readInbound());
        assertEquals(0, closed.get());
        channel.finishAndReleaseAll();
    }

    @Test
    void releasesAConsumedFrameAndForwardsNothing() {
        var channel = channel((ctx, frame) -> FrameTransformHandler.CONSUMED);
        var frame = Unpooled.buffer().writeByte(1);
        channel.writeInbound(frame);
        assertEquals(0, frame.refCnt());
        assertNull(channel.readInbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void releasesTheFrameAndClosesTheSessionWhenTransformThrows() {
        var channel = channel((ctx, frame) -> { throw new IllegalArgumentException("malformed"); });
        var frame = Unpooled.buffer().writeByte(1);
        channel.writeInbound(frame);
        assertEquals(0, frame.refCnt());
        assertNull(channel.readInbound());
        assertEquals(1, closed.get());
        channel.finishAndReleaseAll();
    }

    @Test
    void releasesAnyOtherReferenceCountedMessageAndClosesTheSession() {
        var channel = channel((ctx, frame) -> frame);
        var holder = new DefaultByteBufHolder(Unpooled.buffer().writeByte(1));
        channel.writeInbound(holder);
        assertEquals(0, holder.refCnt());
        assertEquals(1, closed.get());
        channel.finishAndReleaseAll();
    }

    @Test
    void downstreamHandlersReceiveFramesInOrder() {
        var seen = new AtomicInteger();
        var channel = new EmbeddedChannel(new FrameTransformHandler(closed::incrementAndGet) {
            @Override protected ByteBuf transform(ChannelHandlerContext ctx, ByteBuf frame) { return frame; }
        }, new ChannelInboundHandlerAdapter() {
            @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
                seen.set(seen.get() * 10 + ((ByteBuf) message).readByte());
                ((ByteBuf) message).release();
            }
        });
        for (int i = 1; i <= 3; i++) channel.writeInbound(Unpooled.buffer().writeByte(i));
        assertEquals(123, seen.get());
        channel.finishAndReleaseAll();
    }
}

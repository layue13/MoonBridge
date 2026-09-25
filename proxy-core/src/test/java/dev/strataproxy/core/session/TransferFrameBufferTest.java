package dev.strataproxy.core.session;

import dev.strataproxy.core.protocol.ProtocolProfile;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

final class TransferFrameBufferTest {
    @Test
    void replaysFramesInOrderAndTransfersOwnership() {
        var buffer = new TransferFrameBuffer(() -> { });
        var channel = new EmbeddedChannel(buffer);
        try {
            ByteBuf first = Unpooled.wrappedBuffer(new byte[]{1});
            ByteBuf second = Unpooled.wrappedBuffer(new byte[]{2});
            channel.writeInbound(first, second);
            assertNull(channel.readInbound());

            channel.eventLoop().execute(buffer::drainAndRemove);
            channel.runPendingTasks();
            assertSame(first, channel.readInbound());
            assertSame(second, channel.readInbound());
            first.release();
            second.release();
            assertEquals(0, first.refCnt());
            assertEquals(0, second.refCnt());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void closingBeforeReplayReleasesQueuedFrame() {
        var channel = new EmbeddedChannel(new TransferFrameBuffer(() -> { }));
        ByteBuf queued = Unpooled.wrappedBuffer(new byte[]{3});
        channel.writeInbound(queued);
        channel.close();
        channel.runPendingTasks();
        assertEquals(0, queued.refCnt());
        channel.finishAndReleaseAll();
    }

    @Test
    void exceedingCutoverLimitRejectsTheNewFrame() {
        var overflows = new AtomicInteger();
        var channel = new EmbeddedChannel(new TransferFrameBuffer(overflows::incrementAndGet));
        try {
            ByteBuf first = Unpooled.wrappedBuffer(new byte[ProtocolProfile.minecraft1710().maxFrameBytes() + 3]);
            ByteBuf overflow = Unpooled.wrappedBuffer(new byte[]{4});
            channel.writeInbound(first, overflow);
            assertEquals(1, overflows.get());
            assertEquals(0, overflow.refCnt());
            assertEquals(1, first.refCnt());
            channel.close();
            channel.runPendingTasks();
            assertEquals(0, first.refCnt());
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}

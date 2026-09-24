package dev.strataproxy.core.relay;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RawRelayTest {
    @Test
    void forwardsTheOriginalBufferAndClosesBothSides() {
        var client = new EmbeddedChannel();
        var backend = new EmbeddedChannel();
        try {
            RawRelay.attach(client, backend).start();
            pump(client, backend);

            var bytes = Unpooled.wrappedBuffer(new byte[] {1, 2, 3});
            client.writeInbound(bytes);
            pump(client, backend);
            var forwarded = backend.readOutbound();
            assertSame(bytes, forwarded);
            assertEquals(1, bytes.refCnt());
            bytes.release();
            assertEquals(0, bytes.refCnt());

            client.close();
            pump(client, backend);
            assertTrue(!backend.isOpen());
        } finally {
            client.finishAndReleaseAll();
            backend.finishAndReleaseAll();
        }
    }

    @Test
    void waitsForTargetToBecomeWritableBeforeReadingAgain() {
        var reads = new AtomicInteger();
        var client = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void read(ChannelHandlerContext ctx) {
                reads.incrementAndGet();
                ctx.read();
            }
        });
        var backend = new EmbeddedChannel();
        try {
            RawRelay.attach(client, backend).start();
            pump(client, backend);
            var initialReads = reads.get();
            assertTrue(initialReads > 0);

            backend.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
            pump(client, backend);
            client.writeInbound(Unpooled.wrappedBuffer(new byte[] {7}));
            pump(client, backend);
            assertEquals(initialReads, reads.get());

            backend.unsafe().outboundBuffer().setUserDefinedWritability(1, true);
            pump(client, backend);
            assertTrue(reads.get() > initialReads);
        } finally {
            client.finishAndReleaseAll();
            backend.finishAndReleaseAll();
        }
    }

    private static void pump(EmbeddedChannel first, EmbeddedChannel second) {
        first.runPendingTasks();
        second.runPendingTasks();
        first.runPendingTasks();
        second.runPendingTasks();
    }
}

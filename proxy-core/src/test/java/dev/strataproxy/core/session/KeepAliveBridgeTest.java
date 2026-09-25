package dev.strataproxy.core.session;

import dev.strataproxy.core.relay.RawRelay;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class KeepAliveBridgeTest {
    @Test
    void droppingAStaleReplyContinuesManualReads() {
        var state = new KeepAliveBridge.State();
        var reads = new AtomicInteger();
        var frontend = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override public void read(ChannelHandlerContext ctx) {
                reads.incrementAndGet();
                ctx.read();
            }
        }, new KeepAliveBridge(state, true, () -> { }));
        var backend = new EmbeddedChannel(new KeepAliveBridge(state, false, () -> { }));
        try {
            RawRelay.attach(frontend, backend).start();
            pump(frontend, backend);
            backend.writeInbound(frame(101));
            pump(frontend, backend);
            ByteBuf sent = frontend.readOutbound();
            int oldClientId = sent.getInt(sent.readerIndex() + 2);
            sent.release();

            state.switchBackend();
            int readsBeforeDrop = reads.get();
            frontend.writeInbound(frame(oldClientId));
            pump(frontend, backend);
            assertTrue(reads.get() > readsBeforeDrop);
            assertNull(backend.readOutbound());
        } finally {
            frontend.finishAndReleaseAll();
            backend.finishAndReleaseAll();
        }
    }

    @Test
    void ordinaryFramesRemainZeroCopyAndStaleRepliesAreReleased() {
        var state = new KeepAliveBridge.State();
        var closed = new AtomicBoolean();
        var backend = new EmbeddedChannel(new KeepAliveBridge(state, false, () -> closed.set(true)));
        var frontend = new EmbeddedChannel(new KeepAliveBridge(state, true, () -> closed.set(true)));
        try {
            ByteBuf ordinary = Unpooled.wrappedBuffer(new byte[]{2, 3, 42});
            backend.writeInbound(ordinary);
            ByteBuf forwarded = backend.readInbound();
            assertSame(ordinary, forwarded);
            forwarded.release();

            backend.writeInbound(frame(101));
            ByteBuf first = backend.readInbound();
            int firstClientId = first.getInt(first.readerIndex() + 2);
            assertNotEquals(101, firstClientId);
            first.release();
            state.switchBackend();
            ByteBuf stale = frame(firstClientId);
            frontend.writeInbound(stale);
            assertNull(frontend.readInbound());
            assertEquals(0, stale.refCnt());

            backend.writeInbound(frame(202));
            ByteBuf second = backend.readInbound();
            int secondClientId = second.getInt(second.readerIndex() + 2);
            assertNotEquals(firstClientId, secondClientId);
            second.release();
            frontend.writeInbound(frame(secondClientId));
            ByteBuf reply = frontend.readInbound();
            assertEquals(202, reply.getInt(reply.readerIndex() + 2));
            reply.release();
            assertTrue(!closed.get());
        } finally {
            frontend.finishAndReleaseAll();
            backend.finishAndReleaseAll();
        }
    }

    private static ByteBuf frame(int id) {
        return Unpooled.buffer(6, 6).writeByte(5).writeByte(0).writeInt(id);
    }

    private static void pump(EmbeddedChannel first, EmbeddedChannel second) {
        first.runPendingTasks();
        second.runPendingTasks();
        first.runPendingTasks();
        second.runPendingTasks();
    }
}

package dev.strataproxy.core.relay;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RawRelayTest {
    @Test
    void pauseDuringStartupDrainLeavesTheRelayPaused() throws Exception {
        var holdFirst = new AtomicBoolean(true);
        var heldContext = new AtomicReference<ChannelHandlerContext>();
        var heldMessage = new AtomicReference<Object>();
        var heldPromise = new AtomicReference<ChannelPromise>();
        var client = new EmbeddedChannel();
        var backend = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
                if (holdFirst.compareAndSet(true, false)) {
                    heldContext.set(ctx);
                    heldMessage.set(message);
                    heldPromise.set(promise);
                } else {
                    ctx.write(message, promise);
                }
            }
        });
        try {
            var link = RawRelay.attach(client, backend);
            pump(client, backend);
            var first = Unpooled.wrappedBuffer(new byte[] {1});
            client.writeInbound(first);
            pump(client, backend);

            link.start();
            pump(client, backend);
            assertSame(first, heldMessage.get());
            var pausing = link.pause().toCompletableFuture();
            pump(client, backend);
            assertTrue(!pausing.isDone());

            Object pending = heldMessage.getAndSet(null);
            heldContext.get().writeAndFlush(pending, heldPromise.get());
            for (int i = 0; i < 8 && !pausing.isDone(); i++) pump(client, backend);
            assertTrue(heldPromise.get().isDone(), "held outbound promise must complete");
            pausing.get(1, TimeUnit.SECONDS);
            var forwarded = (io.netty.buffer.ByteBuf) backend.readOutbound();
            assertSame(first, forwarded);
            forwarded.release();

            var second = Unpooled.wrappedBuffer(new byte[] {2});
            client.writeInbound(second);
            pump(client, backend);
            assertNull(backend.readOutbound());
            assertEquals(1, second.refCnt());
        } finally {
            Object pending = heldMessage.getAndSet(null);
            if (pending != null) io.netty.util.ReferenceCountUtil.release(pending);
            client.finishAndReleaseAll();
            backend.finishAndReleaseAll();
        }
    }

    @Test
    void holdsAnInFlightReadUntilTheRelayStarts() {
        var client = new EmbeddedChannel();
        var backend = new EmbeddedChannel();
        try {
            var observed = new AtomicInteger();
            var link = RawRelay.attach(client, backend, bytes -> observed.incrementAndGet(), null);
            pump(client, backend);

            var inFlight = Unpooled.wrappedBuffer(new byte[] {42});
            client.writeInbound(inFlight);
            pump(client, backend);
            assertNull(backend.readOutbound());
            assertEquals(1, inFlight.refCnt());
            assertEquals(0, observed.get());

            link.start();
            pump(client, backend);
            var forwarded = (io.netty.buffer.ByteBuf) backend.readOutbound();
            assertSame(inFlight, forwarded);
            assertEquals(1, observed.get());
            forwarded.release();
            assertEquals(0, inFlight.refCnt());
        } finally {
            client.finishAndReleaseAll();
            backend.finishAndReleaseAll();
        }
    }

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

    @Test
    void pausedLinkCanResumeOrDetachWithoutClosingTheClient() throws Exception {
        var client = new EmbeddedChannel();
        var backend = new EmbeddedChannel();
        try {
            var link = RawRelay.attach(client, backend);
            link.start();
            pump(client, backend);

            var paused = link.pause().toCompletableFuture();
            pump(client, backend);
            paused.get(1, TimeUnit.SECONDS);
            var queued = Unpooled.wrappedBuffer(new byte[] {1});
            client.writeInbound(queued);
            pump(client, backend);
            assertNull(backend.readOutbound());

            var resumed = link.resume().toCompletableFuture();
            pump(client, backend);
            resumed.get(1, TimeUnit.SECONDS);
            var first = (io.netty.buffer.ByteBuf) backend.readOutbound();
            assertSame(queued, first);
            assertEquals(1, first.readUnsignedByte());
            first.release();
            client.writeInbound(Unpooled.wrappedBuffer(new byte[] {2}));
            pump(client, backend);
            var forwarded = (io.netty.buffer.ByteBuf) backend.readOutbound();
            assertEquals(2, forwarded.readUnsignedByte());
            forwarded.release();

            var detached = link.detach().toCompletableFuture();
            pump(client, backend);
            detached.get(1, TimeUnit.SECONDS);
            backend.close();
            pump(client, backend);
            assertTrue(client.isOpen());
        } finally {
            client.finishAndReleaseAll();
            backend.finishAndReleaseAll();
        }
    }

    @Test
    void detachWithQueuedMessagesFailsWithoutDetachingEitherSide() throws Exception {
        var client = new EmbeddedChannel();
        var backend = new EmbeddedChannel();
        try {
            var link = RawRelay.attach(client, backend);
            link.start();
            pump(client, backend);
            var paused = link.pause().toCompletableFuture();
            pump(client, backend);
            paused.get(1, TimeUnit.SECONDS);

            var queued = Unpooled.wrappedBuffer(new byte[] {3, 4});
            client.writeInbound(queued);
            pump(client, backend);
            var detached = link.detach().toCompletableFuture();
            pump(client, backend);
            assertTrue(detached.isCompletedExceptionally());
            assertTrue(client.isOpen());
            assertTrue(backend.isOpen());

            var resumed = link.resume().toCompletableFuture();
            pump(client, backend);
            resumed.get(1, TimeUnit.SECONDS);
            var forwarded = (io.netty.buffer.ByteBuf) backend.readOutbound();
            assertSame(queued, forwarded);
            assertEquals(3, forwarded.readUnsignedByte());
            assertEquals(4, forwarded.readUnsignedByte());
            forwarded.release();

            assertEquals(0, queued.refCnt());
            var detachedAfterDrain = link.detach().toCompletableFuture();
            pump(client, backend);
            detachedAfterDrain.get(1, TimeUnit.SECONDS);
            assertTrue(client.isOpen());
            assertTrue(backend.isOpen());
        } finally {
            client.finishAndReleaseAll();
            backend.finishAndReleaseAll();
        }
    }

    @Test
    void lateArrivalDuringRemovalCannotHalfDetachOrLoseOwnership() throws Exception {
        var client = new EmbeddedChannel();
        var backend = new EmbeddedChannel();
        try {
            var link = RawRelay.attach(client, backend);
            link.start();
            pump(client, backend);
            var paused = link.pause().toCompletableFuture();
            pump(client, backend);
            paused.get(1, TimeUnit.SECONDS);

            var detaching = link.detach().toCompletableFuture();
            client.runPendingTasks();
            backend.runPendingTasks();
            var queued = Unpooled.wrappedBuffer(new byte[] {9});
            client.eventLoop().execute(() -> client.writeInbound(queued));
            pump(client, backend);

            detaching.get(1, TimeUnit.SECONDS);
            assertNull(client.pipeline().get("raw-relay"));
            assertNull(backend.pipeline().get("raw-relay"));
            var forwarded = (io.netty.buffer.ByteBuf) client.readInbound();
            assertSame(queued, forwarded);
            forwarded.release();
        } finally {
            client.finishAndReleaseAll();
            backend.finishAndReleaseAll();
        }
    }

    @Test
    void pausedQueueOverflowClosesBothSidesAndReleasesEveryBuffer() throws Exception {
        var client = new EmbeddedChannel();
        var backend = new EmbeddedChannel();
        try {
            var link = RawRelay.attach(client, backend);
            link.start();
            pump(client, backend);
            var paused = link.pause().toCompletableFuture();
            pump(client, backend);
            paused.get(1, TimeUnit.SECONDS);

            var first = Unpooled.buffer(RawRelay.MAX_PAUSED_BYTES, RawRelay.MAX_PAUSED_BYTES)
                    .writeZero(RawRelay.MAX_PAUSED_BYTES);
            var overflow = Unpooled.wrappedBuffer(new byte[] {1});
            client.writeInbound(first);
            client.writeInbound(overflow);
            pump(client, backend);

            assertEquals(0, first.refCnt());
            assertEquals(0, overflow.refCnt());
            assertTrue(!client.isOpen());
            assertTrue(!backend.isOpen());
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

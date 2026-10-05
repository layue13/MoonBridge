package dev.moonbridge.core.session;

import dev.moonbridge.api.AccessDecision;
import dev.moonbridge.api.event.Event;
import dev.moonbridge.core.event.EventDispatcher;
import dev.moonbridge.testing.TrackingAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.DefaultByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The gate buffers client bytes until a plugin decides; every exit must release what it buffered. */
final class ConnectionGateTest {
    private static final class Fixture {
        final CompletableFuture<AccessDecision> decision = new CompletableFuture<>();
        final AtomicInteger accepted = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        final TrackingAllocator allocator = new TrackingAllocator();
        final EmbeddedChannel channel;

        Fixture(Runnable onAccepted) { this(Duration.ofSeconds(5), onAccepted); }

        Fixture(Duration timeout, Runnable onAccepted) {
            EventDispatcher events = new EventDispatcher() {
                @Override public boolean hasSubscribers(Class<?> eventType) { return true; }
                @SuppressWarnings("unchecked")
                @Override public <R> CompletionStage<R> dispatch(Event<R> event) {
                    return (CompletionStage<R>) decision;
                }
            };
            var gate = new ConnectionGate(events, timeout, () -> { accepted.incrementAndGet(); onAccepted.run(); },
                    closed::incrementAndGet);
            channel = new EmbeddedChannel(gate) {
                @Override protected SocketAddress remoteAddress0() {
                    return isActive() ? new InetSocketAddress("127.0.0.1", 40000) : null;
                }
            };
            channel.config().setAllocator(allocator);
        }

        void settle() { channel.runPendingTasks(); }
    }

    private static ByteBuf bytes(int... values) {
        ByteBuf buffer = Unpooled.buffer();
        for (int value : values) buffer.writeByte(value);
        return buffer;
    }

    @Test
    void replaysBufferedBytesOnceAccessIsAllowed() {
        var f = new Fixture(() -> { });
        var first = bytes(1, 2);
        var second = bytes(3);
        f.channel.writeInbound(first);
        f.channel.writeInbound(second);
        assertEquals(0, first.refCnt(), "input is copied into the pending buffer and released");
        assertEquals(0, second.refCnt());
        assertNull(f.channel.readInbound(), "nothing is forwarded before the decision");

        f.decision.complete(AccessDecision.allow());
        f.settle();

        assertEquals(1, f.accepted.get());
        ByteBuf replay = f.channel.readInbound();
        byte[] replayed = new byte[replay.readableBytes()];
        replay.readBytes(replayed);
        assertArrayEquals(new byte[] {1, 2, 3}, replayed);
        replay.release();
        assertTrue(f.allocator.allReleased(), String.valueOf(f.allocator.referenceCounts()));
        assertEquals(0, f.closed.get());
        f.channel.finishAndReleaseAll();
    }

    @Test
    void deniedDecisionReleasesBufferedBytesAndClosesTheSession() {
        var f = new Fixture(() -> { });
        f.channel.writeInbound(bytes(1));
        f.decision.complete(AccessDecision.deny("no"));
        f.settle();
        assertEquals(1, f.closed.get());
        assertEquals(0, f.accepted.get());
        assertTrue(f.allocator.allReleased(), String.valueOf(f.allocator.referenceCounts()));
        f.channel.finishAndReleaseAll();
    }

    @Test
    void failedAcceptanceReleasesBufferedBytes() {
        var f = new Fixture(() -> { throw new IllegalStateException("cannot accept"); });
        f.channel.writeInbound(bytes(1));
        f.decision.complete(AccessDecision.allow());
        f.settle();
        assertEquals(1, f.closed.get());
        assertNull(f.channel.readInbound());
        assertTrue(f.allocator.allReleased(), String.valueOf(f.allocator.referenceCounts()));
        f.channel.finishAndReleaseAll();
    }

    @Test
    void closingBeforeTheDecisionReleasesBufferedBytes() {
        var f = new Fixture(() -> { });
        f.channel.writeInbound(bytes(1, 2, 3));
        f.channel.close();
        f.settle();
        assertTrue(f.allocator.allReleased(), String.valueOf(f.allocator.referenceCounts()));
        f.channel.finishAndReleaseAll();
    }

    @Test
    void deadlineDeniesAndReleasesBufferedBytes() {
        var f = new Fixture(Duration.ofSeconds(5), () -> { });
        f.channel.writeInbound(bytes(1));
        f.channel.advanceTimeBy(6, TimeUnit.SECONDS);
        f.channel.runScheduledPendingTasks();
        assertEquals(1, f.closed.get());
        assertTrue(f.allocator.allReleased(), String.valueOf(f.allocator.referenceCounts()));
        f.channel.finishAndReleaseAll();
    }

    @Test
    void oversizedPreDecisionInputIsReleasedAndDenied() {
        var f = new Fixture(() -> { });
        f.channel.writeInbound(bytes(1));
        var huge = Unpooled.wrappedBuffer(new byte[8192]);
        f.channel.writeInbound(huge);
        assertEquals(0, huge.refCnt());
        assertEquals(1, f.closed.get());
        assertTrue(f.allocator.allReleased(), String.valueOf(f.allocator.referenceCounts()));
        f.channel.finishAndReleaseAll();
    }

    @Test
    void nonBufferMessagesAreReleasedAndDenied() {
        var f = new Fixture(() -> { });
        var holder = new DefaultByteBufHolder(Unpooled.buffer().writeByte(1));
        f.channel.writeInbound(holder);
        assertEquals(0, holder.refCnt());
        assertEquals(1, f.closed.get());
        f.channel.finishAndReleaseAll();
    }
}

package dev.moonbridge.core.session;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TransitionBufferTest {
    private static ByteBuf bytes(int count) {
        return Unpooled.buffer().writeZero(count);
    }

    @Test
    void keepsFramesInOrderAndHandsOwnershipToThePoller() {
        var buffer = new TransitionBuffer(4, 100);
        var first = bytes(3);
        var second = bytes(5);
        assertTrue(buffer.add(true, first));
        assertTrue(buffer.add(false, second));
        assertEquals(2, first.refCnt(), "each add retains the packet once");
        assertEquals(2, second.refCnt());

        var polled = buffer.poll();
        assertTrue(polled.fromFrontend());
        assertEquals(3, polled.payload().readableBytes());
        polled.payload().release();
        var next = buffer.poll();
        assertFalse(next.fromFrontend());
        next.payload().release();
        assertNull(buffer.poll());
        assertTrue(buffer.isEmpty());
        first.release();
        second.release();
        assertEquals(0, first.refCnt());
        assertEquals(0, second.refCnt());
    }

    @Test
    void refusesAFrameBeyondTheFrameBoundWithoutRetainingIt() {
        var buffer = new TransitionBuffer(2, 100);
        var a = bytes(1);
        var b = bytes(1);
        var third = bytes(1);
        assertTrue(buffer.add(true, a));
        assertTrue(buffer.add(true, b));
        assertFalse(buffer.add(true, third));
        assertEquals(1, third.refCnt());
        assertEquals(2, buffer.size());
        buffer.release();
        a.release();
        b.release();
        third.release();
    }

    @Test
    void refusesAFrameBeyondTheByteBoundWithoutRetainingIt() {
        var buffer = new TransitionBuffer(10, 10);
        var a = bytes(4);
        var b = bytes(4);
        var tooBig = bytes(3);
        assertTrue(buffer.add(true, a));
        assertTrue(buffer.add(true, b));
        assertFalse(buffer.add(true, tooBig), "eight queued bytes plus three exceed ten");
        assertEquals(1, tooBig.refCnt());
        buffer.poll().payload().release();
        assertTrue(buffer.add(true, tooBig), "polling frees byte budget");
        buffer.release();
        a.release();
        b.release();
        tooBig.release();
    }

    @Test
    void releaseFreesEveryQueuedFrame() {
        var buffer = new TransitionBuffer(4, 100);
        var packet = bytes(3);
        buffer.add(true, packet);
        buffer.add(false, packet);
        assertEquals(3, packet.refCnt());
        buffer.release();
        assertEquals(1, packet.refCnt(), "only the caller's own reference remains");
        assertTrue(buffer.isEmpty());
        assertNull(buffer.poll());
        packet.release();
    }
}

package dev.moonbridge.testing;

import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records every buffer it hands out, so a test can assert that none is left unreleased. */
public final class TrackingAllocator extends AbstractByteBufAllocator {
    private final List<ByteBuf> allocated = new CopyOnWriteArrayList<>();

    @Override protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
        return track(UnpooledByteBufAllocator.DEFAULT.heapBuffer(initialCapacity, maxCapacity));
    }

    @Override protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
        return track(UnpooledByteBufAllocator.DEFAULT.directBuffer(initialCapacity, maxCapacity));
    }

    @Override public boolean isDirectBufferPooled() { return false; }

    private ByteBuf track(ByteBuf buffer) {
        allocated.add(buffer);
        return buffer;
    }

    public List<ByteBuf> allocated() { return allocated; }

    public boolean allReleased() { return allocated.stream().allMatch(buffer -> buffer.refCnt() == 0); }

    public List<Integer> referenceCounts() { return allocated.stream().map(ByteBuf::refCnt).toList(); }
}

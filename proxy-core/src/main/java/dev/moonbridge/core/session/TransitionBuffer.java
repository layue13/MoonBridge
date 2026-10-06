package dev.moonbridge.core.session;

import io.netty.buffer.ByteBuf;

import java.util.ArrayDeque;

/** Bounded queue of frames read while the relay starts; it owns every buffer it holds until polled. */
final class TransitionBuffer {
    record Frame(boolean fromFrontend, ByteBuf payload) { }

    private final int maxFrames;
    private final int maxBytes;
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private int bytes;

    TransitionBuffer(int maxFrames, int maxBytes) {
        this.maxFrames = maxFrames;
        this.maxBytes = maxBytes;
    }

    /** Retains a duplicate of {@code packet}; returns false, retaining nothing, when a bound would be exceeded. */
    boolean add(boolean fromFrontend, ByteBuf packet) {
        int size = packet.readableBytes();
        if (frames.size() >= maxFrames || bytes + size > maxBytes) return false;
        frames.addLast(new Frame(fromFrontend, packet.retainedDuplicate()));
        bytes += size;
        return true;
    }

    boolean isEmpty() { return frames.isEmpty(); }

    int size() { return frames.size(); }

    /** Transfers ownership of the oldest frame to the caller, or returns null when empty. */
    Frame poll() {
        Frame frame = frames.pollFirst();
        if (frame != null) bytes -= frame.payload().readableBytes();
        return frame;
    }

    /** Releases everything still queued. */
    void release() {
        Frame frame;
        while ((frame = frames.pollFirst()) != null) frame.payload().release();
        bytes = 0;
    }
}

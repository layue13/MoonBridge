package dev.strataproxy.core.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.Objects;
import java.util.function.Consumer;

/** Observes framed packets in a raw TCP stream without copying complete frames. */
public final class PacketStreamTap implements AutoCloseable {
    private final int maxFrameBytes;
    private final Consumer<ByteBuf> packetObserver;
    private ByteBuf pending;
    private boolean closed;

    public PacketStreamTap(int maxFrameBytes, Consumer<ByteBuf> packetObserver) {
        if (maxFrameBytes < 1) throw new IllegalArgumentException("maxFrameBytes must be positive");
        this.maxFrameBytes = maxFrameBytes;
        this.packetObserver = Objects.requireNonNull(packetObserver, "packetObserver");
    }

    /** The input and packet slices remain owned by the caller and are valid only during this call. */
    public void accept(ByteBuf bytes) {
        if (closed) throw new IllegalStateException("packet tap is closed");
        ByteBuf input = Objects.requireNonNull(bytes, "bytes").duplicate();
        if (pending != null) {
            pending.writeBytes(input, input.readerIndex(), input.readableBytes());
            drain(pending);
            if (pending != null && !pending.isReadable()) {
                pending.release();
                pending = null;
            }
        } else {
            drain(input);
        }
    }

    private void drain(ByteBuf input) {
        while (input.isReadable()) {
            int start = input.readerIndex();
            long header = probe(input);
            if (header < 0) {
                saveRemainder(input);
                return;
            }
            int length = (int) (header >>> 3);
            int headerBytes = (int) (header & 7);
            if (length < 1 || length > maxFrameBytes) throw new ProtocolException("invalid PLAY frame length");
            if (input.readableBytes() < headerBytes + length) {
                saveRemainder(input);
                return;
            }
            input.skipBytes(headerBytes);
            packetObserver.accept(input.readSlice(length));
            if (input.readerIndex() <= start) throw new IllegalStateException("packet tap made no progress");
        }
        if (input == pending) input.discardReadBytes();
    }

    private void saveRemainder(ByteBuf input) {
        if (input == pending) {
            input.discardReadBytes();
            if (input.readableBytes() > maxFrameBytes + 5) throw new ProtocolException("partial frame exceeds limit");
            return;
        }
        int remaining = input.readableBytes();
        if (remaining > maxFrameBytes + 5) throw new ProtocolException("partial frame exceeds limit");
        pending = Unpooled.buffer(remaining);
        pending.writeBytes(input);
    }

    /** High bits are the frame length; low three bits are its VarInt header length. */
    private static long probe(ByteBuf input) {
        int length = 0;
        int start = input.readerIndex();
        for (int index = 0; index < 5; index++) {
            if (input.writerIndex() <= start + index) return -1;
            int value = input.getUnsignedByte(start + index);
            length |= (value & 0x7F) << (7 * index);
            if ((value & 0x80) == 0) return ((long) length << 3) | (index + 1);
        }
        throw new ProtocolException("PLAY frame length VarInt exceeds five bytes");
    }

    @Override public void close() {
        closed = true;
        if (pending != null) {
            pending.release();
            pending = null;
        }
    }
}

package dev.moonbridge.clientws.net;

import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

/**
 * Client-side WebSocket frame encoder that sends one binary message per batch of writes.
 *
 * <p>
 * Vanilla calls {@code writeAndFlush} once per packet from the client thread, and Netty runs each of those as its own
 * event-loop task. Plain {@link ByteBuf} writes are therefore queued here, and a flush only schedules one task at the
 * end of the event loop's queue; by the time it runs, every packet the client thread queued in the meantime has been
 * written too, and the whole batch goes out as one binary message (split at {@link #MAX_MESSAGE_PAYLOAD}). A lone
 * packet still leaves in the same loop iteration: the task runs right after the write that scheduled it. A write's
 * promise completes when the message carrying its last byte has been written.
 *
 * <p>
 * Each message is one buffer: the header, then the payload masked four bytes at a time. {@link WebSocketFrame}s are
 * encoded as they come; a pong is flushed at once and may overtake queued data (control frames may be sent between
 * messages), any other frame first writes the queued data so it keeps its place. A close of the channel writes and
 * flushes queued data first, since its writer already flushed it.
 */
public final class ClientFrameEncoder extends ChannelDuplexHandler {

    /** Largest payload of one binary message; matches the proxy's outbound cap. */
    public static final int MAX_MESSAGE_PAYLOAD = 64 * 1024;

    static final int OPCODE_CONTINUATION = 0x0;
    static final int OPCODE_TEXT = 0x1;
    static final int OPCODE_BINARY = 0x2;
    static final int OPCODE_CLOSE = 0x8;
    static final int OPCODE_PING = 0x9;
    static final int OPCODE_PONG = 0xA;

    /** Queued writes as alternating {@link ByteBuf} and {@link ChannelPromise}. */
    private final ArrayDeque<Object> pending = new ArrayDeque<Object>();
    private int pendingBytes;
    private boolean flushScheduled;
    private ChannelHandlerContext ctx;
    private final Runnable flushTask = new Runnable() {

        @Override
        public void run() {
            flushScheduled = false;
            if (!pending.isEmpty()) {
                writePending(ctx);
                ctx.flush();
            }
        }
    };

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            if (!buf.isReadable()) {
                buf.release();
                promise.setSuccess();
                return;
            }
            pending.add(buf);
            pending.add(promise);
            pendingBytes += buf.readableBytes();
        } else if (msg instanceof PongWebSocketFrame) {
            ctx.write(encode(ctx, (WebSocketFrame) msg), promise);
            ctx.flush();
        } else if (msg instanceof WebSocketFrame) {
            writePending(ctx);
            ctx.write(encode(ctx, (WebSocketFrame) msg), promise);
        } else {
            ctx.write(msg, promise);
        }
    }

    @Override
    public void flush(ChannelHandlerContext ctx) throws Exception {
        if (pending.isEmpty()) {
            ctx.flush();
        } else if (!flushScheduled) {
            flushScheduled = true;
            ctx.executor()
                .execute(flushTask);
        }
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        if (!pending.isEmpty()) {
            writePending(ctx);
            ctx.flush();
        }
        ctx.close(promise);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        failPending(new ClosedChannelException());
        ctx.fireChannelInactive();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        failPending(new ClosedChannelException());
    }

    private void failPending(Throwable cause) {
        Object o;
        while ((o = pending.poll()) != null) {
            ((ByteBuf) o).release();
            ((ChannelPromise) pending.poll()).tryFailure(cause);
        }
        pendingBytes = 0;
    }

    /** Writes everything queued as binary messages of at most {@link #MAX_MESSAGE_PAYLOAD} bytes, without flushing. */
    private void writePending(ChannelHandlerContext ctx) {
        while (pendingBytes > 0) {
            int length = Math.min(pendingBytes, MAX_MESSAGE_PAYLOAD);
            int mask = ThreadLocalRandom.current()
                .nextInt();
            ByteBuf out = ctx.alloc()
                .buffer(headerLength(length) + length);
            writeHeader(out, true, OPCODE_BINARY, length, mask);
            ChannelPromise single = null;
            PromiseFanOut fanOut = null;
            int offset = 0;
            while (offset < length) {
                ByteBuf buf = (ByteBuf) pending.peek();
                int n = Math.min(buf.readableBytes(), length - offset);
                writeMasked(out, buf, buf.readerIndex(), n, Integer.rotateLeft(mask, 8 * (offset & 3)));
                buf.skipBytes(n);
                offset += n;
                if (buf.isReadable()) {
                    break;
                }
                pending.poll();
                buf.release();
                ChannelPromise done = (ChannelPromise) pending.poll();
                if (single == null) {
                    single = done;
                } else {
                    if (fanOut == null) {
                        fanOut = new PromiseFanOut(single);
                    }
                    fanOut.add(done);
                }
            }
            pendingBytes -= length;
            if (fanOut != null) {
                ctx.write(out)
                    .addListener(fanOut);
            } else if (single != null) {
                ctx.write(out, single);
            } else {
                ctx.write(out);
            }
        }
    }

    /** Encodes a single frame object into one masked buffer and releases the frame. */
    static ByteBuf encode(ChannelHandlerContext ctx, WebSocketFrame frame) {
        try {
            ByteBuf payload = frame.content();
            int length = payload.readableBytes();
            int mask = ThreadLocalRandom.current()
                .nextInt();
            ByteBuf out = ctx.alloc()
                .buffer(headerLength(length) + length);
            out.writeByte((frame.isFinalFragment() ? 0x80 : 0) | (frame.rsv() & 7) << 4 | opcode(frame));
            writeLength(out, length, mask);
            writeMasked(out, payload, payload.readerIndex(), length, mask);
            return out;
        } finally {
            frame.release();
        }
    }

    private static int opcode(WebSocketFrame frame) {
        if (frame instanceof BinaryWebSocketFrame) {
            return OPCODE_BINARY;
        } else if (frame instanceof PongWebSocketFrame) {
            return OPCODE_PONG;
        } else if (frame instanceof CloseWebSocketFrame) {
            return OPCODE_CLOSE;
        } else if (frame instanceof PingWebSocketFrame) {
            return OPCODE_PING;
        } else if (frame instanceof ContinuationWebSocketFrame) {
            return OPCODE_CONTINUATION;
        } else if (frame instanceof TextWebSocketFrame) {
            return OPCODE_TEXT;
        }
        throw new UnsupportedOperationException(
            "cannot encode " + frame.getClass()
                .getName());
    }

    static int headerLength(int payloadLength) {
        return 2 + (payloadLength < 126 ? 0 : payloadLength <= 0xFFFF ? 2 : 8) + 4;
    }

    static void writeHeader(ByteBuf out, boolean fin, int opcode, int length, int mask) {
        out.writeByte((fin ? 0x80 : 0) | opcode);
        writeLength(out, length, mask);
    }

    private static void writeLength(ByteBuf out, int length, int mask) {
        if (length < 126) {
            out.writeByte(0x80 | length);
        } else if (length <= 0xFFFF) {
            out.writeByte(0x80 | 126);
            out.writeShort(length);
        } else {
            out.writeByte(0x80 | 127);
            out.writeLong(length);
        }
        out.writeInt(mask);
    }

    /**
     * Appends {@code length} bytes of {@code src} from {@code index}, XORed with {@code mask} whose most significant
     * byte applies to the first byte. Callers rotate the mask when the bytes do not start at a multiple of four.
     */
    static void writeMasked(ByteBuf out, ByteBuf src, int index, int length, int mask) {
        int end = index + length;
        int i = index;
        for (; end - i >= 4; i += 4) {
            out.writeInt(src.getInt(i) ^ mask);
        }
        for (int shift = 24; i < end; i++, shift -= 8) {
            out.writeByte(src.getByte(i) ^ mask >>> shift);
        }
    }

    /** Completes the promises of every write whose last byte went out in one message. */
    private static final class PromiseFanOut implements ChannelFutureListener {

        private ChannelPromise[] promises = new ChannelPromise[4];
        private int size;

        PromiseFanOut(ChannelPromise first) {
            add(first);
        }

        void add(ChannelPromise promise) {
            if (size == promises.length) {
                promises = Arrays.copyOf(promises, size * 2);
            }
            promises[size++] = promise;
        }

        @Override
        public void operationComplete(ChannelFuture future) {
            for (int i = 0; i < size; i++) {
                if (future.isSuccess()) {
                    promises[i].trySuccess();
                } else {
                    promises[i].tryFailure(future.cause());
                }
            }
        }
    }
}

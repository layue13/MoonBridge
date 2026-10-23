package dev.moonbridge.clientws.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;

class ClientFrameEncoderTest {

    /** One decoded client frame: first byte, unmasked payload. */
    static final class Frame {

        final int b0;
        final byte[] payload;
        final int lengthBytes;

        Frame(int b0, byte[] payload, int lengthBytes) {
            this.b0 = b0;
            this.payload = payload;
            this.lengthBytes = lengthBytes;
        }
    }

    /** Parses exactly one masked frame filling {@code buf}, and releases it. */
    static Frame parse(ByteBuf buf) {
        try {
            int b0 = buf.readUnsignedByte();
            int b1 = buf.readUnsignedByte();
            assertTrue((b1 & 0x80) != 0, "client frame must be masked");
            long len = b1 & 0x7F;
            int lengthBytes = 0;
            if (len == 126) {
                len = buf.readUnsignedShort();
                lengthBytes = 2;
            } else if (len == 127) {
                len = buf.readLong();
                lengthBytes = 8;
            }
            byte[] mask = new byte[4];
            buf.readBytes(mask);
            byte[] payload = new byte[(int) len];
            buf.readBytes(payload);
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i & 3];
            }
            assertFalse(buf.isReadable(), "one frame per buffer");
            return new Frame(b0, payload, lengthBytes);
        } finally {
            buf.release();
        }
    }

    private static byte[] bytes(Random r, int n) {
        byte[] b = new byte[n];
        r.nextBytes(b);
        return b;
    }

    private static List<Frame> drain(EmbeddedChannel ch) {
        ch.runPendingTasks();
        List<Frame> frames = new ArrayList<Frame>();
        Object o;
        while ((o = ch.readOutbound()) != null) {
            frames.add(parse((ByteBuf) o));
        }
        return frames;
    }

    @Test
    void maskingRoundTripsAtEveryLengthAndOffsetModFour() {
        Random r = new Random(1);
        // Two writes per message: the second starts at every offset mod 4 and has every length mod 4.
        for (int first = 0; first < 8; first++) {
            for (int second = 1; second < 9; second++) {
                EmbeddedChannel ch = new EmbeddedChannel(new ClientFrameEncoder());
                byte[] a = bytes(r, first);
                byte[] b = bytes(r, second);
                ch.write(Unpooled.wrappedBuffer(a));
                ch.writeAndFlush(Unpooled.wrappedBuffer(b));
                List<Frame> frames = drain(ch);
                assertEquals(1, frames.size());
                assertEquals(0x82, frames.get(0).b0);
                ByteArrayOutputStream expected = new ByteArrayOutputStream();
                expected.write(a, 0, a.length);
                expected.write(b, 0, b.length);
                assertArrayEquals(expected.toByteArray(), frames.get(0).payload, first + "+" + second);
                assertFalse(ch.finish());
            }
        }
    }

    @Test
    void lengthEncodingsAtTheBoundaries() {
        Random r = new Random(2);
        int[] lengths = { 125, 126, 65535, 65536 };
        int[] lengthBytes = { 0, 2, 2, 8 };
        for (int i = 0; i < lengths.length; i++) {
            EmbeddedChannel ch = new EmbeddedChannel(new ClientFrameEncoder());
            byte[] data = bytes(r, lengths[i]);
            // Odd source offset: the payload is not 4-aligned in its buffer.
            ByteBuf src = Unpooled.buffer(data.length + 1)
                .writeByte(0)
                .writeBytes(data);
            src.skipBytes(1);
            ch.writeAndFlush(src);
            List<Frame> frames = drain(ch);
            assertEquals(1, frames.size(), "length " + lengths[i]);
            assertEquals(lengthBytes[i], frames.get(0).lengthBytes, "length " + lengths[i]);
            assertArrayEquals(data, frames.get(0).payload);
            assertEquals(0, src.refCnt());
            assertFalse(ch.finish());
        }
    }

    @Test
    void messagesAreCappedAt64KiB() {
        Random r = new Random(3);
        EmbeddedChannel ch = new EmbeddedChannel(new ClientFrameEncoder());
        byte[] big = bytes(r, ClientFrameEncoder.MAX_MESSAGE_PAYLOAD + 5);
        byte[] small = bytes(r, 7);
        ChannelFuture bigDone = ch.write(Unpooled.wrappedBuffer(big));
        ChannelFuture smallDone = ch.writeAndFlush(Unpooled.wrappedBuffer(small));
        List<Frame> frames = drain(ch);
        assertEquals(2, frames.size());
        assertEquals(ClientFrameEncoder.MAX_MESSAGE_PAYLOAD, frames.get(0).payload.length);
        assertEquals(12, frames.get(1).payload.length);
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (Frame f : frames) {
            all.write(f.payload, 0, f.payload.length);
        }
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.write(big, 0, big.length);
        expected.write(small, 0, small.length);
        assertArrayEquals(expected.toByteArray(), all.toByteArray());
        assertTrue(bigDone.isSuccess());
        assertTrue(smallDone.isSuccess());
        assertFalse(ch.finish());
    }

    @Test
    void queuedWritesShareOneMessageAndAllPromisesComplete() {
        EmbeddedChannel ch = new EmbeddedChannel(new ClientFrameEncoder());
        List<ChannelFuture> futures = new ArrayList<ChannelFuture>();
        for (int i = 0; i < 5; i++) {
            futures.add(ch.writeAndFlush(Unpooled.wrappedBuffer(new byte[] { (byte) i, (byte) i })));
        }
        assertNull(ch.readOutbound(), "nothing goes out before the batch task runs");
        for (ChannelFuture f : futures) {
            assertFalse(f.isDone());
        }
        List<Frame> frames = drain(ch);
        assertEquals(1, frames.size());
        assertArrayEquals(new byte[] { 0, 0, 1, 1, 2, 2, 3, 3, 4, 4 }, frames.get(0).payload);
        for (ChannelFuture f : futures) {
            assertTrue(f.isSuccess());
        }
        assertFalse(ch.finish());
    }

    @Test
    void emptyWritesSucceedWithoutAFrame() {
        EmbeddedChannel ch = new EmbeddedChannel(new ClientFrameEncoder());
        ByteBuf empty = Unpooled.buffer();
        assertTrue(
            ch.writeAndFlush(empty)
                .isSuccess());
        assertEquals(0, empty.refCnt());
        assertTrue(drain(ch).isEmpty());
        assertFalse(ch.finish());
    }

    @Test
    void pongIsFlushedImmediatelyAheadOfQueuedData() {
        EmbeddedChannel ch = new EmbeddedChannel(new ClientFrameEncoder());
        ch.writeAndFlush(Unpooled.wrappedBuffer(new byte[] { 1, 2, 3 }));
        ByteBuf ping = Unpooled.wrappedBuffer(new byte[] { 'p' });
        ch.writeAndFlush(new PongWebSocketFrame(ping));
        Frame pong = parse((ByteBuf) ch.readOutbound());
        assertEquals(0x8A, pong.b0);
        assertArrayEquals(new byte[] { 'p' }, pong.payload);
        assertEquals(0, ping.refCnt());
        assertNull(ch.readOutbound(), "queued data waits for its batch task");
        List<Frame> frames = drain(ch);
        assertEquals(1, frames.size());
        assertEquals(0x82, frames.get(0).b0);
        assertFalse(ch.finish());
    }

    @Test
    void closeFrameAndChannelCloseSendQueuedDataFirst() {
        EmbeddedChannel ch = new EmbeddedChannel(new ClientFrameEncoder());
        ch.writeAndFlush(Unpooled.wrappedBuffer(new byte[] { 9 }));
        ch.writeAndFlush(new CloseWebSocketFrame(1000, ""));
        Frame data = parse((ByteBuf) ch.readOutbound());
        assertArrayEquals(new byte[] { 9 }, data.payload);
        Frame close = parse((ByteBuf) ch.readOutbound());
        assertEquals(0x88, close.b0);
        assertArrayEquals(new byte[] { 0x03, (byte) 0xE8 }, close.payload);

        ch.writeAndFlush(Unpooled.wrappedBuffer(new byte[] { 7, 7 }));
        ch.close();
        Frame last = parse((ByteBuf) ch.readOutbound());
        assertArrayEquals(new byte[] { 7, 7 }, last.payload);
        assertFalse(ch.finish());
    }

    @Test
    void unflushedWritesFailAndAreReleasedWhenTheChannelGoesInactive() {
        EmbeddedChannel ch = new EmbeddedChannel(new ClientFrameEncoder());
        ByteBuf data = Unpooled.wrappedBuffer(new byte[] { 1 });
        ChannelFuture f = ch.write(data);
        ch.pipeline()
            .fireChannelInactive();
        assertEquals(0, data.refCnt());
        assertFalse(f.isSuccess());
        assertTrue(f.isDone());
        ch.finish();
    }

    /** Head-side stand-in for the socket: records each written buffer's frame, completes the write. */
    static final class Recorder extends ChannelOutboundHandlerAdapter {

        final List<Frame> frames = new ArrayList<Frame>();

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            frames.add(parse((ByteBuf) msg));
            promise.setSuccess();
        }

        @Override
        public void flush(ChannelHandlerContext ctx) {}
    }

    private static Channel registered(NioEventLoopGroup group, Recorder recorder) {
        Channel ch = new NioSocketChannel();
        ch.pipeline()
            .addLast(recorder, new ClientFrameEncoder());
        group.register(ch)
            .syncUninterruptibly();
        return ch;
    }

    @Test
    void lonePacketLeavesInTheTaskRightAfterItsWrite() throws Exception {
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        try {
            final Recorder recorder = new Recorder();
            final Channel ch = registered(group, recorder);
            final AtomicReference<String> seenByNextTask = new AtomicReference<String>();
            final CountDownLatch done = new CountDownLatch(1);
            ch.eventLoop()
                .execute(() -> {
                    final ChannelFuture write = ch.writeAndFlush(Unpooled.wrappedBuffer(new byte[] { 42 }));
                    // Anything queued after the write runs after the batch task: no wait for another I/O pass.
                    ch.eventLoop()
                        .execute(() -> {
                            seenByNextTask.set(recorder.frames.size() + " " + write.isSuccess());
                            done.countDown();
                        });
                });
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertEquals("1 true", seenByNextTask.get());
            assertArrayEquals(new byte[] { 42 }, recorder.frames.get(0).payload);
            ch.close()
                .syncUninterruptibly();
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS)
                .syncUninterruptibly();
        }
    }

    @Test
    void packetsQueuedByAnotherThreadWhileTheLoopIsBusyShareOneMessage() throws Exception {
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        try {
            final Recorder recorder = new Recorder();
            final Channel ch = registered(group, recorder);
            final CountDownLatch release = new CountDownLatch(1);
            ch.eventLoop()
                .execute(() -> {
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread()
                            .interrupt();
                    }
                });
            // Like vanilla's client thread: one writeAndFlush per packet, each queued as its own loop task.
            List<ChannelFuture> writes = new ArrayList<ChannelFuture>();
            ByteArrayOutputStream expected = new ByteArrayOutputStream();
            for (int i = 0; i < 20; i++) {
                byte[] packet = new byte[i + 1];
                Arrays.fill(packet, (byte) i);
                expected.write(packet, 0, packet.length);
                writes.add(ch.writeAndFlush(Unpooled.wrappedBuffer(packet)));
            }
            release.countDown();
            for (ChannelFuture f : writes) {
                assertTrue(f.await(10, TimeUnit.SECONDS) && f.isSuccess());
            }
            List<Frame> frames = ch.eventLoop()
                .submit(() -> new ArrayList<Frame>(recorder.frames))
                .get(10, TimeUnit.SECONDS);
            assertEquals(1, frames.size());
            assertArrayEquals(expected.toByteArray(), frames.get(0).payload);
            ch.close()
                .syncUninterruptibly();
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS)
                .syncUninterruptibly();
        }
    }
}

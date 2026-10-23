package dev.moonbridge.clientws.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

class ClientFrameDecoderTest {

    /** Server frame with any flags and the shortest length form, or a forced one ({@code lengthBytes} 0, 2 or 8). */
    static byte[] frame(int b0, byte[] payload, int lengthBytes) {
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write(b0);
        if (lengthBytes == 0) {
            f.write(payload.length);
        } else if (lengthBytes == 2) {
            f.write(126);
            f.write(payload.length >>> 8);
            f.write(payload.length & 0xff);
        } else {
            f.write(127);
            for (int shift = 56; shift >= 0; shift -= 8) {
                f.write((int) ((long) payload.length >>> shift) & 0xff);
            }
        }
        f.write(payload, 0, payload.length);
        return f.toByteArray();
    }

    static byte[] frame(int b0, byte[] payload) {
        return frame(b0, payload, payload.length < 126 ? 0 : payload.length <= 0xFFFF ? 2 : 8);
    }

    private static byte[] bytes(Random r, int n) {
        byte[] b = new byte[n];
        r.nextBytes(b);
        return b;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            o.write(p, 0, p.length);
        }
        return o.toByteArray();
    }

    /** Pooled direct buffers, so Netty 4.0.10's leak detector tracks them (heap buffers are not tracked). */
    private static ByteBuf tracked(byte[] b, int from, int to) {
        return PooledByteBufAllocator.DEFAULT.directBuffer(to - from)
            .writeBytes(b, from, to - from);
    }

    @Test
    void binaryPayloadIsARetainedSliceOfTheInput() {
        EmbeddedChannel ch = new EmbeddedChannel(new ClientFrameDecoder());
        byte[] payload = { 1, 2, 3, 4, 5 };
        byte[] wire = frame(0x82, payload);
        ByteBuf in = tracked(wire, 0, wire.length);
        ch.writeInbound(in);
        ByteBuf out = (ByteBuf) ch.readInbound();
        assertSame(in, out.unwrap(), "a slice of the received buffer, not a copy");
        assertEquals(1, in.refCnt(), "the decoder dropped its reference; the slice holds the only one");
        byte[] got = new byte[out.readableBytes()];
        out.readBytes(got);
        assertArrayEquals(payload, got);
        out.release();
        assertEquals(0, in.refCnt());
        assertFalse(ch.finish());
    }

    @Test
    void reassemblesAStreamSplitAtEveryPoint() {
        Random r = new Random(7);
        byte[] a = bytes(r, 300); // 16-bit length
        byte[] b = bytes(r, 70000); // 64-bit length
        byte[] c1 = bytes(r, 2);
        byte[] c2 = bytes(r, 125);
        byte[] wire = concat(
            frame(0x82, a),
            frame(0x02, c1), // fragmented message with a ping in between
            frame(0x89, new byte[] { 'p', 'i' }),
            frame(0x80, c2),
            frame(0x82, b),
            frame(0x82, new byte[0]));
        byte[] expected = concat(a, c1, c2, b);
        int[] splits = new int[400];
        for (int i = 0; i < splits.length; i++) {
            splits[i] = i; // every split inside the first frames' heads and payload starts
        }
        for (int split : splits) {
            check(wire, expected, new int[] { split });
        }
        // And arbitrary chunking, including 1-byte reads over the whole of a small stream.
        for (int chunk : new int[] { 1, 3, 7, 1460 }) {
            List<Integer> points = new ArrayList<Integer>();
            for (int p = chunk; p < wire.length && points.size() < 5000; p += chunk) {
                points.add(p);
            }
            int[] cuts = new int[points.size()];
            for (int i = 0; i < cuts.length; i++) {
                cuts[i] = points.get(i);
            }
            check(wire, expected, cuts);
        }
    }

    private static void check(byte[] wire, byte[] expected, int[] cuts) {
        EmbeddedChannel ch = new EmbeddedChannel(new ClientFrameDecoder());
        List<ByteBuf> inputs = new ArrayList<ByteBuf>();
        int from = 0;
        for (int i = 0; i <= cuts.length; i++) {
            int to = i < cuts.length ? cuts[i] : wire.length;
            if (to <= from) {
                continue;
            }
            ByteBuf in = tracked(wire, from, to);
            inputs.add(in);
            ch.writeInbound(in);
            from = to;
        }
        ByteArrayOutputStream got = new ByteArrayOutputStream();
        int pings = 0;
        Object o;
        while ((o = ch.readInbound()) != null) {
            if (o instanceof PingWebSocketFrame) {
                PingWebSocketFrame ping = (PingWebSocketFrame) o;
                assertEquals(
                    "pi",
                    ping.content()
                        .toString(StandardCharsets.US_ASCII));
                ping.release();
                pings++;
                continue;
            }
            ByteBuf buf = (ByteBuf) o;
            assertTrue(buf.isReadable(), "no empty slices");
            byte[] b = new byte[buf.readableBytes()];
            buf.readBytes(b);
            got.write(b, 0, b.length);
            buf.release();
        }
        assertEquals(1, pings);
        assertArrayEquals(expected, got.toByteArray());
        for (ByteBuf in : inputs) {
            assertEquals(0, in.refCnt(), "every received buffer is released once its slices are");
        }
        assertFalse(ch.finish());
    }

    @Test
    void controlFramesBecomeFrameObjects() {
        EmbeddedChannel ch = new EmbeddedChannel(new ClientFrameDecoder());
        ch.writeInbound(
            Unpooled.wrappedBuffer(
                concat(
                    frame(0x8A, new byte[] { 1 }),
                    frame(0x88, new byte[] { 0x03, (byte) 0xE8, 'o', 'k' }),
                    frame(0x82, new byte[] { 5 }))));
        PongWebSocketFrame pong = (PongWebSocketFrame) ch.readInbound();
        assertEquals(
            1,
            pong.content()
                .readableBytes());
        pong.release();
        CloseWebSocketFrame close = (CloseWebSocketFrame) ch.readInbound();
        assertEquals(1000, close.statusCode());
        assertEquals("ok", close.reasonText());
        close.release();
        assertNull(ch.readInbound(), "input after close is discarded");
        assertFalse(ch.finish());
    }

    @Test
    void textFrameIsReportedAndTheRestDiscarded() {
        EmbeddedChannel ch = new EmbeddedChannel(new ClientFrameDecoder());
        ByteBuf in = Unpooled
            .wrappedBuffer(concat(frame(0x81, new byte[] { 'h', 'i' }), frame(0x82, new byte[] { 1 })));
        ch.writeInbound(in);
        TextWebSocketFrame text = (TextWebSocketFrame) ch.readInbound();
        text.release();
        assertNull(ch.readInbound());
        assertEquals(0, in.refCnt());
        assertFalse(ch.finish());
    }

    @Test
    void protocolViolationsCloseAndRaise() {
        byte[][] bad = {
            // masked server frame
            { (byte) 0x82, (byte) 0x81, 0, 0, 0, 0, 1 },
            // RSV1
            { (byte) 0xC2, 1, 1 },
            // fragmented ping
            { 0x09, 0 },
            // control frame over 125 bytes
            frame(0x89, new byte[126]),
            // reserved control and data opcodes
            { (byte) 0x8B, 0 }, { (byte) 0x83, 0 },
            // continuation without a message
            { (byte) 0x80, 1, 1 },
            // new message inside a fragmented one
            concat(frame(0x02, new byte[] { 1 }), frame(0x82, new byte[] { 2 })),
            // non-minimal lengths
            frame(0x82, new byte[10], 2), frame(0x82, new byte[300], 8),
            // close with a 1-byte body, a reserved status code, invalid UTF-8
            { (byte) 0x88, 1, 3 }, frame(0x88, new byte[] { 0x03, (byte) 0xED }),
            frame(0x88, new byte[] { 0x03, (byte) 0xE8, (byte) 0xC3 }),
            // over the frame limit (64 here)
            frame(0x82, new byte[65]), };
        for (byte[] wire : bad) {
            EmbeddedChannel ch = new EmbeddedChannel(new ClientFrameDecoder(64));
            ByteBuf in = Unpooled.wrappedBuffer(wire);
            try {
                ch.writeInbound(in);
                ch.checkException();
                throw new AssertionError("accepted " + Arrays.toString(wire));
            } catch (CorruptedFrameException expected) {
                // good
            }
            assertFalse(ch.isOpen(), Arrays.toString(wire));
            // Payloads that arrived before the violation were passed on; release them.
            Object o;
            while ((o = ch.readInbound()) != null) {
                ((ByteBuf) o).release();
            }
            assertEquals(0, in.refCnt(), Arrays.toString(wire));
            Object written;
            while ((written = ch.readOutbound()) != null) {
                ((ByteBuf) written).release();
            }
            ch.finish();
        }
    }

    @Test
    void frameAtTheLimitIsAccepted() {
        EmbeddedChannel ch = new EmbeddedChannel(new ClientFrameDecoder(64));
        ch.writeInbound(Unpooled.wrappedBuffer(frame(0x82, new byte[64])));
        ByteBuf out = (ByteBuf) ch.readInbound();
        assertEquals(64, out.readableBytes());
        out.release();
        assertFalse(ch.finish());
    }

    @Test
    void carriedHeadIsReleasedOnClose() {
        EmbeddedChannel ch = new EmbeddedChannel(new ClientFrameDecoder());
        ByteBuf in = tracked(new byte[] { (byte) 0x82, 126, 1 }, 0, 3);
        ch.writeInbound(in);
        assertNull(ch.readInbound());
        assertEquals(0, in.refCnt());
        assertFalse(ch.finish());
    }
}

package dev.moonbridge.clientws.net;

import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocket13FrameDecoder;
import io.netty.handler.codec.http.websocketx.WebSocket13FrameEncoder;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

/**
 * Client codec microbenchmark: Netty 4.0.10's {@code WebSocket13FrameEncoder/Decoder} ("base", with the frame
 * wrapping the old adapter did) against {@link ClientFrameEncoder}/{@link ClientFrameDecoder} ("candidate").
 *
 * <p>
 * Encode: a non-loop thread plays vanilla's client thread and calls {@code writeAndFlush} once per packet, in bursts of
 * 1–24 packets, waiting for each burst to be written. A sink at the head of the pipeline stands in for the socket.
 * Decode: a server stream (a ~2 MiB login burst in 32 KiB frames, then play traffic as the proxy sends it: batches of
 * 1–32 packets per message, at most 64 KiB) is fed to the pipeline in 1460-byte reads on the event loop.
 *
 * <p>
 * Packet sizes: 88% 20–300 B, 11% 2–8 KiB, 1% 30–60 KiB, fixed seed. CPU and allocation are those of the event loop
 * thread (where both codecs run); buffers are unpooled heap buffers so the JVM's per-thread allocation counter sees
 * ByteBuf memory too. Usage: {@code --variant base|candidate --repeats N} (the first phase of each mode is warm-up).
 */
public final class CodecBenchmark {

    static final long SEED = 20261023L;
    static final int ENCODE_PACKETS = 100_000;
    static final int DECODE_PACKETS = 40_000;
    static final int LOGIN_BURST = 2 * 1024 * 1024;
    static final int LOGIN_FRAME = 32 * 1024;
    static final int READ_SIZE = 1460;
    static final int DECODE_PASSES = 8;
    static final ByteBufAllocator ALLOC = new UnpooledByteBufAllocator(false);
    static final byte[] CONTENT = new byte[64 * 1024];

    static {
        new Random(SEED).nextBytes(CONTENT);
    }

    private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory
        .getThreadMXBean();

    static int packetSize(Random r) {
        int p = r.nextInt(1000);
        if (p < 880) {
            return 20 + r.nextInt(281);
        } else if (p < 990) {
            return 2048 + r.nextInt(6145);
        }
        return 30720 + r.nextInt(30721);
    }

    public static void main(String[] args) throws Exception {
        String variant = "candidate";
        int repeats = 3;
        String label = "run";
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--variant")) {
                variant = args[++i];
            } else if (args[i].equals("--repeats")) {
                repeats = Integer.parseInt(args[++i]);
            } else if (args[i].equals("--label")) {
                label = args[++i];
            }
        }
        boolean candidate = variant.equals("candidate");
        if (!candidate && !variant.equals("base")) {
            throw new IllegalArgumentException("--variant base|candidate");
        }
        System.out.println(
            "# java " + System.getProperty("java.version")
                + " "
                + System.getProperty("java.vm.name")
                + ", "
                + System.getProperty("os.name")
                + " "
                + System.getProperty("os.arch")
                + ", cpus "
                + Runtime.getRuntime()
                    .availableProcessors());
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        try {
            Random r = new Random(SEED);
            int[] sizes = new int[ENCODE_PACKETS];
            for (int i = 0; i < sizes.length; i++) {
                sizes[i] = packetSize(r);
            }
            int[] bursts = new int[ENCODE_PACKETS];
            for (int i = 0; i < bursts.length; i++) {
                bursts[i] = 1 + r.nextInt(24);
            }
            byte[] stream = serverStream(new Random(SEED + 1));
            for (int rep = 0; rep <= repeats; rep++) {
                String phase = rep == 0 ? "warmup" : "rep" + rep;
                System.out.println(
                    "encode " + variant + " " + label + " " + phase + ": " + encode(group, candidate, sizes, bursts));
                System.out.println(
                    "decode " + variant + " " + label + " " + phase + ": " + decode(group, candidate, stream, false));
                System.out.println(
                    "decodesplit " + variant
                        + " "
                        + label
                        + " "
                        + phase
                        + ": "
                        + decode(group, candidate, stream, true));
            }
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS)
                .syncUninterruptibly();
        }
    }

    // ---- encode ----

    /** Socket stand-in: counts and releases what the encoder writes. */
    static final class Sink extends ChannelOutboundHandlerAdapter {

        long buffers;
        long bytes;
        long flushes;

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            ByteBuf b = (ByteBuf) msg;
            buffers++;
            bytes += b.readableBytes();
            b.release();
            promise.setSuccess();
        }

        @Override
        public void flush(ChannelHandlerContext ctx) {
            flushes++;
        }
    }

    /** Base only: counts frames entering Netty's encoder (it writes a header and a body buffer per frame). */
    static final class FrameCounter extends ChannelOutboundHandlerAdapter {

        long frames;

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (msg instanceof WebSocketFrame) {
                frames++;
            }
            ctx.write(msg, promise);
        }
    }

    /** Base only: what the old WebSocketFrameAdapter did on both paths. */
    static final class LegacyFrames extends io.netty.channel.ChannelDuplexHandler {

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            ByteBuf buf = (ByteBuf) msg;
            if (buf.isReadable()) {
                ctx.write(new BinaryWebSocketFrame(buf), promise);
            } else {
                buf.release();
                promise.setSuccess();
            }
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof BinaryWebSocketFrame || msg instanceof ContinuationWebSocketFrame) {
                ctx.fireChannelRead(((WebSocketFrame) msg).content());
            } else {
                ctx.fireChannelRead(msg);
            }
        }
    }

    private static Channel channel(NioEventLoopGroup group) {
        Channel ch = new NioSocketChannel();
        ch.config()
            .setOption(ChannelOption.ALLOCATOR, ALLOC);
        return ch;
    }

    private static long[] loopCounters(Channel ch) throws Exception {
        return ch.eventLoop()
            .submit(new Callable<long[]>() {

                @Override
                public long[] call() {
                    long id = Thread.currentThread()
                        .getId();
                    return new long[] { THREADS.getThreadCpuTime(id), THREADS.getThreadAllocatedBytes(id) };
                }
            })
            .get();
    }

    static String encode(NioEventLoopGroup group, boolean candidate, int[] sizes, int[] bursts) throws Exception {
        Channel ch = channel(group);
        Sink sink = new Sink();
        FrameCounter counter = new FrameCounter();
        if (candidate) {
            ch.pipeline()
                .addLast(sink, new ClientFrameEncoder());
        } else {
            ch.pipeline()
                .addLast(sink, new WebSocket13FrameEncoder(true), counter, new LegacyFrames());
        }
        group.register(ch)
            .syncUninterruptibly();
        long payload = 0;
        long[] before = loopCounters(ch);
        long producerCpu = THREADS.getCurrentThreadCpuTime();
        long start = System.nanoTime();
        int i = 0;
        int b = 0;
        while (i < sizes.length) {
            int burst = Math.min(bursts[b++], sizes.length - i);
            ChannelFuture last = null;
            for (int k = 0; k < burst; k++, i++) {
                payload += sizes[i];
                last = ch.writeAndFlush(Unpooled.wrappedBuffer(CONTENT, (i * 61) & 1023, sizes[i]));
            }
            last.syncUninterruptibly();
        }
        long wall = System.nanoTime() - start;
        producerCpu = THREADS.getCurrentThreadCpuTime() - producerCpu;
        long[] after = loopCounters(ch);
        long messages = candidate ? sink.buffers : counter.frames;
        ch.close()
            .syncUninterruptibly();
        long cpu = after[0] - before[0];
        long alloc = after[1] - before[1];
        int packets = sizes.length;
        return String.format(
            Locale.ROOT,
            "packets=%d payloadBytes=%d messages=%d messagesPer1000=%.1f flushesPer1000=%.1f wireBytes=%d"
                + " loopCpuNsPerPacket=%.0f loopCpuNsPerByte=%.3f loopAllocBytesPerPacket=%.1f"
                + " producerCpuNsPerPacket=%.0f wallMs=%.1f",
            packets,
            payload,
            messages,
            1000.0 * messages / packets,
            1000.0 * sink.flushes / packets,
            sink.bytes,
            (double) cpu / packets,
            (double) cpu / payload,
            (double) alloc / packets,
            (double) producerCpu / packets,
            wall / 1e6);
    }

    // ---- decode ----

    /** Server frame head, unmasked. */
    private static void head(ByteArrayOutputStream out, int length) {
        out.write(0x82);
        if (length < 126) {
            out.write(length);
        } else if (length <= 0xFFFF) {
            out.write(126);
            out.write(length >>> 8);
            out.write(length & 0xFF);
        } else {
            out.write(127);
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) ((long) length >>> shift) & 0xFF);
            }
        }
    }

    /** Appends one Minecraft frame (VarInt length, then the body) of {@code size} body bytes. */
    private static void packet(ByteArrayOutputStream out, Random r, int size) {
        int v = size;
        while ((v & ~0x7F) != 0) {
            out.write(v & 0x7F | 0x80);
            v >>>= 7;
        }
        out.write(v);
        int off = r.nextInt(1024);
        int remaining = size;
        while (remaining > 0) {
            int n = Math.min(remaining, CONTENT.length - off);
            out.write(CONTENT, off, n);
            remaining -= n;
            off = 0;
        }
    }

    /** Appends {@code bytes} as binary messages of at most {@code max} payload bytes. */
    private static int frames(ByteArrayOutputStream out, byte[] bytes, int max) {
        int messages = 0;
        for (int off = 0; off < bytes.length; off += max) {
            int n = Math.min(max, bytes.length - off);
            head(out, n);
            out.write(bytes, off, n);
            messages++;
        }
        return messages;
    }

    static long streamPayload;
    static int streamMessages;
    static int streamPackets;

    /** The proxy's view: a login burst in 32 KiB frames, then batches of 1–32 packets per message of ≤ 64 KiB. */
    static byte[] serverStream(Random r) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024 * 1024);
        ByteArrayOutputStream mc = new ByteArrayOutputStream();
        int packets = 0;
        while (mc.size() < LOGIN_BURST) {
            packet(mc, r, packetSize(r));
            packets++;
        }
        long payload = mc.size();
        int messages = frames(out, mc.toByteArray(), LOGIN_FRAME);
        int play = 0;
        while (play < DECODE_PACKETS) {
            int batch = 1 + r.nextInt(32);
            mc.reset();
            for (int k = 0; k < batch && play < DECODE_PACKETS; k++, play++) {
                packet(mc, r, packetSize(r));
            }
            payload += mc.size();
            messages += frames(out, mc.toByteArray(), ClientFrameEncoder.MAX_MESSAGE_PAYLOAD);
        }
        streamPayload = payload;
        streamMessages = messages;
        streamPackets = packets + play;
        return out.toByteArray();
    }

    /** End of the measured pipeline: counts and releases. */
    static final class ByteSink extends ChannelInboundHandlerAdapter {

        long bytes;
        long buffers;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf b = (ByteBuf) msg;
            bytes += b.readableBytes();
            buffers++;
            b.release();
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {}
    }

    /** Same logic as vanilla 1.7.10's {@code MessageDeserializer2} ("splitter"): one copied buffer per packet. */
    static final class VanillaSplitter extends ByteToMessageDecoder {

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            in.markReaderIndex();
            byte[] varint = new byte[3];
            for (int i = 0; i < varint.length; i++) {
                if (!in.isReadable()) {
                    in.resetReaderIndex();
                    return;
                }
                varint[i] = in.readByte();
                if (varint[i] >= 0) {
                    ByteBuf lengthBuf = Unpooled.wrappedBuffer(varint);
                    try {
                        int length = 0;
                        int shift = 0;
                        byte b;
                        do {
                            b = lengthBuf.readByte();
                            length |= (b & 0x7F) << shift;
                            shift += 7;
                        } while ((b & 0x80) != 0);
                        if (in.readableBytes() < length) {
                            in.resetReaderIndex();
                            return;
                        }
                        out.add(in.readBytes(length));
                        return;
                    } finally {
                        lengthBuf.release();
                    }
                }
            }
            throw new CorruptedFrameException("length wider than 21-bit");
        }
    }

    /**
     * Decodes {@link #DECODE_PASSES} copies of {@code stream}; with {@code split}, vanilla's splitter runs behind the
     * codec, so the cost of handing it slices instead of whole payloads shows up.
     */
    static String decode(NioEventLoopGroup group, final boolean candidate, byte[] stream, boolean split)
        throws Exception {
        final Channel ch = channel(group);
        final ByteSink sink = new ByteSink();
        if (candidate) {
            ch.pipeline()
                .addLast(new ClientFrameDecoder(), new WebSocketFrameAdapter());
        } else {
            ch.pipeline()
                .addLast(
                    new WebSocket13FrameDecoder(false, false, WebSocketPipeline.MAX_FRAME_PAYLOAD),
                    new LegacyFrames(),
                    new WebSocketFrameAdapter());
        }
        if (split) {
            ch.pipeline()
                .addLast(new VanillaSplitter());
        }
        ch.pipeline()
            .addLast(sink);
        group.register(ch)
            .syncUninterruptibly();
        // Each pass feeds the whole stream; the reads are prepared before the measured part of each pass.
        long[] result = new long[3];
        for (int pass = 0; pass < DECODE_PASSES; pass++) {
            final List<ByteBuf> reads = new ArrayList<ByteBuf>(stream.length / READ_SIZE + 1);
            for (int off = 0; off < stream.length; off += READ_SIZE) {
                int n = Math.min(READ_SIZE, stream.length - off);
                reads.add(
                    ALLOC.heapBuffer(READ_SIZE)
                        .writeBytes(stream, off, n));
            }
            long[] one = ch.eventLoop()
                .submit(new Callable<long[]>() {

                    @Override
                    public long[] call() {
                        long id = Thread.currentThread()
                            .getId();
                        long cpu = THREADS.getThreadCpuTime(id);
                        long alloc = THREADS.getThreadAllocatedBytes(id);
                        long start = System.nanoTime();
                        for (ByteBuf read : reads) {
                            ch.pipeline()
                                .fireChannelRead(read);
                            ch.pipeline()
                                .fireChannelReadComplete();
                        }
                        return new long[] { THREADS.getThreadCpuTime(id) - cpu,
                            THREADS.getThreadAllocatedBytes(id) - alloc, System.nanoTime() - start };
                    }
                })
                .get();
            for (int k = 0; k < 3; k++) {
                result[k] += one[k];
            }
        }
        long expectedBuffers = split ? (long) streamPackets * DECODE_PASSES : -1;
        long expectedBytes = split ? sink.bytes : streamPayload * DECODE_PASSES;
        if (sink.bytes != expectedBytes || split && sink.buffers != expectedBuffers) {
            throw new AssertionError("decoded " + sink.bytes + " bytes in " + sink.buffers + " buffers");
        }
        ch.close()
            .syncUninterruptibly();
        long wire = (long) stream.length * DECODE_PASSES;
        return String.format(
            Locale.ROOT,
            "passes=%d wireBytesPerPass=%d messagesPerPass=%d packetsPerPass=%d readsPerPass=%d buffersOutPerPass=%d"
                + " cpuNsPerByte=%.3f allocBytesPerByte=%.3f wallMs=%.1f",
            DECODE_PASSES,
            stream.length,
            streamMessages,
            streamPackets,
            (stream.length + READ_SIZE - 1) / READ_SIZE,
            sink.buffers / DECODE_PASSES,
            (double) result[0] / wire,
            (double) result[1] / wire,
            result[2] / 1e6);
    }
}

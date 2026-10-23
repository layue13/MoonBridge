package dev.moonbridge.clientws.net;

import java.lang.management.ManagementFactory;
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
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocket13FrameEncoder;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

/**
 * Client encoder microbenchmark: Netty 4.0.10's {@code WebSocket13FrameEncoder} ("base", with the frame wrapping the
 * old adapter did) against {@link ClientFrameEncoder} ("candidate").
 *
 * <p>
 * A non-loop thread plays vanilla's client thread and calls {@code writeAndFlush} once per packet, in bursts of 1–24
 * packets, waiting for each burst to be written. A sink at the head of the pipeline stands in for the socket. Packet
 * sizes: 88% 20–300 B, 11% 2–8 KiB, 1% 30–60 KiB, fixed seed. CPU and allocation are those of the event loop thread
 * (where the codec runs); buffers are unpooled heap buffers so the JVM's per-thread allocation counter sees ByteBuf
 * memory too. Usage: {@code --variant base|candidate --repeats N --label X} (the first phase is warm-up).
 *
 * <p>
 * The decoder candidate measured in testing/benchmarks/results/2026-10-23-client-websocket-codec.md (and rejected) is
 * in commit 1decc44, together with this benchmark's decode modes.
 */
public final class CodecBenchmark {

    static final long SEED = 20261023L;
    static final int ENCODE_PACKETS = 100_000;
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
            for (int rep = 0; rep <= repeats; rep++) {
                String phase = rep == 0 ? "warmup" : "rep" + rep;
                System.out.println(
                    "encode " + variant + " " + label + " " + phase + ": " + encode(group, candidate, sizes, bursts));
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

    /** Base only: what the old WebSocketFrameAdapter did with outbound buffers. */
    static final class LegacyFrames extends ChannelOutboundHandlerAdapter {

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
}

package dev.moonbridge.core.session;

import dev.moonbridge.core.protocol.MinecraftFrameDecoder;
import dev.moonbridge.core.protocol.ProtocolProfile;
import dev.moonbridge.core.protocol.ProtocolVarInt;
import dev.moonbridge.core.relay.RawRelay;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;

/** Synthetic loopback echo benchmark for direct and actual relay pipeline variants. */
public final class RelayBenchmark {
    private enum RelayMode { RAW, FRAMED, FRAMED_KEEP_ALIVE }
    private static final AtomicLong RELAYED = new AtomicLong();
    private final EventLoopGroup acceptors = new NioEventLoopGroup(1);
    private final EventLoopGroup io = new NioEventLoopGroup();
    private final List<Channel> listeners = new ArrayList<>();
    private RelayBenchmark() { }

    public static void main(String[] args) throws Exception {
        int connections = intArg(args, "connections", 4);
        int messages = intArg(args, "messages", 1000);
        int warmup = intArg(args, "warmup", 100);
        int payload = intArg(args, "payload", 1024);
        int repeats = intArg(args, "repeats", 2);
        int window = intArg(args, "window", 1);
        if (connections < 1 || messages < 1 || warmup < 0 || payload < 1 || payload > 1_048_576
                || repeats < 1 || repeats > 10 || window < 1 || window > 1024)
            throw new IllegalArgumentException("connections/messages/payload must be positive; warmup nonnegative; payload <= 1048576; repeats 1..10; window 1..1024");

        var benchmark = new RelayBenchmark();
        try {
            int backendPort = benchmark.startBackend();
            int rawPort = benchmark.startRelay(backendPort, RelayMode.RAW);
            int framedPort = benchmark.startRelay(backendPort, RelayMode.FRAMED);
            int framedKeepAlivePort = benchmark.startRelay(backendPort, RelayMode.FRAMED_KEEP_ALIVE);
            byte[] body = frame(payload);
            System.out.printf("Synthetic loopback TCP echo; frame_payload=%d bytes wire_frame=%d bytes connections=%d measured_messages_per_connection=%d warmup_per_connection=%d repeats=%d window=%d%n",
                    payload, body.length, connections, messages, warmup, repeats, window);
            System.out.println("Comparison: direct echo, byte-stream RawRelay, framed RawRelay, and framed RawRelay with the real KeepAliveBridge; connect/setup time excluded.");
            benchmark.run("baseline-start", backendPort, connections, messages, warmup, window, body);
            for (int round = 1; round <= repeats; round++) {
                if ((round & 1) == 1) {
                    benchmark.run("raw-relay-" + round, rawPort, connections, messages, warmup, window, body);
                    benchmark.run("framed-relay-" + round, framedPort, connections, messages, warmup, window, body);
                    benchmark.run("framed-keepalive-" + round, framedKeepAlivePort, connections, messages, warmup, window, body);
                } else {
                    benchmark.run("framed-keepalive-" + round, framedKeepAlivePort, connections, messages, warmup, window, body);
                    benchmark.run("framed-relay-" + round, framedPort, connections, messages, warmup, window, body);
                    benchmark.run("raw-relay-" + round, rawPort, connections, messages, warmup, window, body);
                }
            }
            benchmark.run("baseline-end", backendPort, connections, messages, warmup, window, body);
            long expectedBytes = (long) connections * (messages + warmup) * body.length * (2 + 3L * repeats);
            if (RELAYED.get() != expectedBytes) throw new IllegalStateException("backend byte count mismatch");
            System.out.printf("backend_received_wire_bytes_all_phases=%d%n", RELAYED.get());
        } finally {
            benchmark.close();
        }
    }

    private int startBackend() throws Exception {
        ChannelFuture bound = new ServerBootstrap().group(acceptors, io).channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                if (msg instanceof ByteBuf buf) RELAYED.addAndGet(buf.readableBytes());
                                ctx.writeAndFlush(msg);
                            }
                        });
                    }
                }).bind("127.0.0.1", 0).sync();
        listeners.add(bound.channel());
        return ((InetSocketAddress) bound.channel().localAddress()).getPort();
    }

    private int startRelay(int backendPort, RelayMode mode) throws Exception {
        ChannelFuture bound = new ServerBootstrap().group(acceptors, io).channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel front) {
                        front.config().setAutoRead(false);
                        if (mode != RelayMode.RAW) front.pipeline().addLast(new MinecraftFrameDecoder(
                                ProtocolProfile.minecraft1710(), true));
                        KeepAliveBridge.State keepAlives = mode == RelayMode.FRAMED_KEEP_ALIVE
                                ? new KeepAliveBridge.State() : null;
                        new Bootstrap().group(front.eventLoop()).channel(NioSocketChannel.class)
                                .option(ChannelOption.TCP_NODELAY, true)
                                .handler(new ChannelInitializer<SocketChannel>() {
                                    @Override protected void initChannel(SocketChannel ch) {
                                        if (mode != RelayMode.RAW) ch.pipeline().addLast(new MinecraftFrameDecoder(
                                                ProtocolProfile.minecraft1710(), true));
                                    }
                                }).connect("127.0.0.1", backendPort).addListener(connect -> {
                                    if (!connect.isSuccess()) { front.close(); return; }
                                    Channel back = ((ChannelFuture) connect).channel();
                                    try {
                                        if (keepAlives != null) {
                                            front.pipeline().addLast(new KeepAliveBridge(keepAlives, true, front::close));
                                            back.pipeline().addLast(new KeepAliveBridge(keepAlives, false, back::close));
                                        }
                                        RawRelay.Link link = RawRelay.attach(front, back);
                                        link.ready().whenComplete((ignored, failure) -> {
                                            if (failure != null) { front.close(); back.close(); }
                                            else link.start();
                                        });
                                    } catch (RuntimeException failure) { front.close(); back.close(); }
                                });
                    }
                }).bind("127.0.0.1", 0).sync();
        listeners.add(bound.channel());
        return ((InetSocketAddress) bound.channel().localAddress()).getPort();
    }

    private static byte[] frame(int payloadBytes) {
        byte[] bytes = new byte[ProtocolVarInt.encodedSize(payloadBytes) + payloadBytes];
        int value = payloadBytes;
        int offset = 0;
        do {
            int part = value & 0x7f;
            value >>>= 7;
            bytes[offset++] = (byte) (value == 0 ? part : part | 0x80);
        } while (value != 0);
        bytes[offset] = 0x03;
        for (int i = offset + 1; i < bytes.length; i++) bytes[i] = (byte) (i * 31 + 7);
        return bytes;
    }

    private void run(String label, int port, int connections, int messages, int warmup, int window,
                     byte[] body) throws Exception {
        List<Worker> workers = new ArrayList<>();
        CountDownLatch ready = new CountDownLatch(connections);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(connections);
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        long heapBefore = memory.getHeapMemoryUsage().getUsed();
        long[] gcBefore = gcTotals();
        long wallStart;
        for (int i = 0; i < connections; i++) {
            Worker worker = new Worker(port, messages, warmup, window, body, ready, start, finished);
            workers.add(worker);
            new Thread(worker, "bench-" + label + "-" + i).start();
        }
        if (!ready.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("clients failed to connect");
        wallStart = System.nanoTime();
        start.countDown();
        if (!finished.await(5, TimeUnit.MINUTES)) throw new IllegalStateException("benchmark timed out");
        for (Worker worker : workers) if (worker.failure != null)
            throw new IllegalStateException("benchmark client failed", worker.failure);
        long elapsed = System.nanoTime() - wallStart;
        long heapAfter = memory.getHeapMemoryUsage().getUsed();
        long[] gcAfter = gcTotals();
        long[] samples = workers.stream().flatMapToLong(w -> Arrays.stream(w.latencies)).toArray();
        Arrays.sort(samples);
        long ops = (long) connections * messages;
        double seconds = elapsed / 1_000_000_000.0;
        double mibps = (ops * (double) body.length / (1024 * 1024)) / seconds;
        System.out.printf("%s: %.1f roundtrips/s, %.2f MiB/s one-way-wire-bytes, latency_ms p50=%.3f p95=%.3f p99=%.3f, gc_collections=%d gc_time_ms=%d heap_used_delta_bytes=%d%n",
                label, ops / seconds, mibps, percentile(samples, .50) / 1e6, percentile(samples, .95) / 1e6,
                percentile(samples, .99) / 1e6, gcAfter[0] - gcBefore[0], gcAfter[1] - gcBefore[1], heapAfter - heapBefore);
    }

    private static long percentile(long[] sorted, double q) {
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(q * sorted.length) - 1)];
    }

    private static long[] gcTotals() {
        long count = 0, millis = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (bean.getCollectionCount() >= 0) count += bean.getCollectionCount();
            if (bean.getCollectionTime() >= 0) millis += bean.getCollectionTime();
        }
        return new long[]{count, millis};
    }

    private void close() {
        for (Channel channel : listeners) channel.close().awaitUninterruptibly();
        io.shutdownGracefully().awaitUninterruptibly();
        acceptors.shutdownGracefully().awaitUninterruptibly();
    }

    private static int intArg(String[] args, String key, int fallback) {
        for (int i = 0; i < args.length; i++) if (args[i].equals("--" + key)) {
            if (i + 1 >= args.length) throw new IllegalArgumentException("missing value for --" + key);
            return Integer.parseInt(args[i + 1]);
        }
        return fallback;
    }

    private static final class Worker implements Runnable {
        private final int port, messages, warmup, window;
        private final byte[] body;
        private final CountDownLatch ready, start, finished;
        private long[] latencies;
        private volatile Exception failure;
        Worker(int port, int messages, int warmup, int window, byte[] body,
               CountDownLatch ready, CountDownLatch start, CountDownLatch finished) {
            this.port = port; this.messages = messages; this.warmup = warmup; this.window = window; this.body = body;
            this.ready = ready; this.start = start; this.finished = finished;
            this.latencies = new long[messages];
        }
        @Override public void run() {
            try (Socket socket = new Socket()) {
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(30_000);
                socket.connect(new InetSocketAddress("127.0.0.1", port), 10_000);
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                byte[] response = new byte[body.length];
                exchangeMessages(socket, out, in, body, response, warmup, window, null);
                ready.countDown();
                start.await();
                exchangeMessages(socket, out, in, body, response, messages, window, latencies);
            } catch (Exception failure) {
                this.failure = failure;
                ready.countDown();
                latencies = new long[0];
            } finally { finished.countDown(); }
        }
        private static void exchangeMessages(Socket socket, OutputStream out, InputStream in,
                                             byte[] body, byte[] response, int count, int window,
                                             long[] latencies) throws Exception {
            if (window == 1) {
                for (int i = 0; i < count; i++) {
                    long sentAt = latencies == null ? 0 : System.nanoTime();
                    out.write(body);
                    out.flush();
                    readEcho(in, body, response);
                    if (latencies != null) latencies[i] = System.nanoTime() - sentAt;
                }
                return;
            }

            Semaphore inFlight = new Semaphore(window);
            AtomicLongArray sentAt = latencies == null ? null : new AtomicLongArray(count);
            AtomicReference<Exception> writerFailure = new AtomicReference<>();
            Thread writer = new Thread(() -> {
                try {
                    for (int i = 0; i < count; i++) {
                        inFlight.acquire();
                        if (sentAt != null) sentAt.set(i, System.nanoTime());
                        out.write(body);
                        out.flush();
                    }
                } catch (Exception failure) {
                    writerFailure.set(failure);
                    try { socket.close(); } catch (Exception ignored) { }
                }
            }, "bench-pipelined-writer");
            writer.start();
            try {
                for (int i = 0; i < count; i++) {
                    readEcho(in, body, response);
                    if (latencies != null) latencies[i] = System.nanoTime() - sentAt.get(i);
                    inFlight.release();
                }
            } catch (Exception failure) {
                writer.interrupt();
                socket.close();
                throw failure;
            } finally {
                writer.join();
            }
            if (writerFailure.get() != null) throw writerFailure.get();
        }

        private static void readEcho(InputStream in, byte[] body, byte[] response) throws Exception {
            int offset = 0;
            while (offset < response.length) {
                int read = in.read(response, offset, response.length - offset);
                if (read < 0) throw new IllegalStateException("echo closed early");
                offset += read;
            }
            if (!Arrays.equals(body, response)) throw new IllegalStateException("echo payload mismatch");
        }
    }
}

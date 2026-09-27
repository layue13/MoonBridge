package dev.moonbridge.core.relay;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.Future;
import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises relay backpressure through real loopback TCP sockets and NIO channels. */
final class RawRelaySocketTest {
    private static final int CHUNK_BYTES = 4 * 1024;
    // Leave room for TCP window updates while constraining NIO reads and outbound writes.
    private static final int SOCKET_RECEIVE_BYTES = 64 * 1024;
    private static final int WRITE_LOW_WATER_MARK = 1024;
    private static final int WRITE_HIGH_WATER_MARK = 2 * 1024;
    private static final int PAYLOAD_BYTES = 8 * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Test
    void stopsReadingUnderSocketPressureThenForwardsEveryByteInBothDirections() throws Exception {
        try (var pair = new SocketPair()) {
            var fromFirst = new AtomicLong();
            var fromSecond = new AtomicLong();
            var link = RawRelay.attach(pair.firstRelay, pair.secondRelay,
                    bytes -> fromFirst.addAndGet(bytes.readableBytes()),
                    bytes -> fromSecond.addAndGet(bytes.readableBytes()));
            link.ready().toCompletableFuture().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            link.start();

            transferWithBackpressure(pair, pair.firstPeer, pair.secondPeer, pair.secondRelay, fromFirst);
            transferWithBackpressure(pair, pair.secondPeer, pair.firstPeer, pair.firstRelay, fromSecond);
        }
    }

    @Test
    void closesThePairAndReleasesPendingWritesWhenCongestedReceiverDisconnects() throws Exception {
        try (var pair = new SocketPair()) {
            var observed = new AtomicLong();
            var link = RawRelay.attach(pair.firstRelay, pair.secondRelay,
                    bytes -> observed.addAndGet(bytes.readableBytes()), null);
            link.ready().toCompletableFuture().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            link.start();

            var payload = payload();
            var sending = pair.workers.submit(() -> { writeFully(pair.firstPeer, payload); return null; });
            awaitStableReads(observed, pair.secondRelay, "receiver remained congested before disconnect");
            assertTrue(pendingBytes(pair.secondRelay) > 0,
                    "nonwritable target should have queued outbound bytes");

            pair.secondPeer.close();
            await(() -> !isActive(pair.firstRelay) && !isActive(pair.secondRelay),
                    "relay pair closed after receiver disconnect");
            await(() -> pendingBytes(pair.secondRelay) == 0,
                    "pending target writes released");
            assertTrue(observed.get() > 0, "the congested relay must have received data before disconnect");
            sending.cancel(true);
        }
    }

    private static void transferWithBackpressure(SocketPair pair, Socket sender, Socket receiver, Channel relayTarget,
                                                 AtomicLong observed) throws Exception {
        var payload = payload();
        var before = observed.get();
        var sending = pair.workers.submit(() -> { writeFully(sender, payload); return null; });

        long bytesWhenBlocked = awaitStableReads(observed, relayTarget,
                "source reads stopped while the actual target socket was nonwritable");
        assertTrue(bytesWhenBlocked > before, "the source socket must deliver bytes before pressure");
        assertTrue(bytesWhenBlocked < before + payload.length, "pressure must stop an unfinished transfer");
        long pending = pendingBytes(relayTarget);
        assertTrue(pending > 0, "target channel must have a real pending socket write");
        assertTrue(pending <= WRITE_HIGH_WATER_MARK + CHUNK_BYTES,
                "pending writes should stay bounded to the watermark plus one receive chunk: " + pending);
        assertEquals(bytesWhenBlocked, observed.get(), "no additional relay reads while target is nonwritable");
        var received = pair.workers.submit(() -> readFully(receiver, payload.length));
        sending.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        byte[] actual = received.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertArrayEquals(payload, actual, "resuming the receiver must preserve the exact byte stream");
        await(() -> observed.get() == before + payload.length, "relay observed the complete source stream");
        assertEquals(before + payload.length, observed.get());
    }

    private static byte[] payload() {
        var bytes = new byte[PAYLOAD_BYTES];
        int state = 0x13579bdf;
        for (int i = 0; i < bytes.length; i++) {
            state ^= state << 13;
            state ^= state >>> 17;
            state ^= state << 5;
            bytes[i] = (byte) state;
        }
        return bytes;
    }

    private static boolean isWritable(Channel channel) {
        return channel.isWritable();
    }

    private static boolean isActive(Channel channel) {
        return channel.isActive();
    }

    private static long pendingBytes(Channel channel) {
        try {
            return channel.eventLoop().submit(() -> {
                var buffer = channel.unsafe().outboundBuffer();
                return buffer == null ? 0L : buffer.totalPendingWriteBytes();
            }).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception failure) {
            throw new AssertionError("Could not inspect relay write queue on its event loop", failure);
        }
    }

    private static void writeFully(Socket socket, byte[] bytes) throws Exception {
        OutputStream output = socket.getOutputStream();
        output.write(bytes);
        output.flush();
    }

    private static byte[] readFully(Socket socket, int length) throws Exception {
        InputStream input = socket.getInputStream();
        var bytes = new byte[length];
        int offset = 0;
        while (offset < bytes.length) {
            int count = input.read(bytes, offset, bytes.length - offset);
            if (count < 0) throw new EOFException("socket closed after " + offset + " of " + length + " bytes");
            offset += count;
        }
        return bytes;
    }

    private static void await(BooleanSupplier condition, String description) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("Timed out waiting for: " + description);
            Thread.sleep(10);
        }
    }

    private static long awaitStableReads(AtomicLong observed, Channel target, String description) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        long stableSince = -1;
        long lastObserved = observed.get();
        while (System.nanoTime() < deadline) {
            long current = observed.get();
            if (current != lastObserved) {
                lastObserved = current;
                stableSince = -1;
            }
            if (!isWritable(target) && current == lastObserved) {
                if (stableSince < 0) stableSince = System.nanoTime();
                if (System.nanoTime() - stableSince >= TimeUnit.MILLISECONDS.toNanos(250)) return current;
            } else {
                stableSince = -1;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Timed out waiting for stable condition: " + description);
    }

    private static final class Worker implements AutoCloseable {
        private final ExecutorService executor = Executors.newFixedThreadPool(2, task -> {
            var thread = new Thread(task, "raw-relay-socket-test-worker");
            thread.setDaemon(true);
            return thread;
        });

        <T> java.util.concurrent.Future<T> submit(java.util.concurrent.Callable<T> task) {
            return executor.submit(task);
        }

        @Override public void close() throws Exception {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "socket worker must terminate within the test timeout");
        }
    }

    private static final class SocketPair implements AutoCloseable {
        private final NioEventLoopGroup boss = new NioEventLoopGroup(1);
        private final NioEventLoopGroup io = new NioEventLoopGroup(2);
        private final ArrayBlockingQueue<Channel> accepted = new ArrayBlockingQueue<>(2);
        private final Worker workers = new Worker();
        private final Channel server;
        private final Socket firstPeer = new Socket();
        private final Socket secondPeer = new Socket();
        private final Channel firstRelay;
        private final Channel secondRelay;

        private SocketPair() throws Exception {
            Channel bound = null;
            Channel first = null;
            Channel second = null;
            try {
                bound = new ServerBootstrap().group(boss, io).channel(NioServerSocketChannel.class)
                        .childOption(ChannelOption.AUTO_READ, false)
                        .childOption(ChannelOption.SO_RCVBUF, SOCKET_RECEIVE_BYTES)
                        .childOption(ChannelOption.SO_SNDBUF, CHUNK_BYTES)
                        .childOption(ChannelOption.TCP_NODELAY, true)
                        .childHandler(new ChannelInitializer<SocketChannel>() {
                            @Override protected void initChannel(SocketChannel channel) {
                                channel.config().setRecvByteBufAllocator(
                                        new FixedRecvByteBufAllocator(CHUNK_BYTES).maxMessagesPerRead(1));
                                channel.config().setWriteBufferWaterMark(new io.netty.channel.WriteBufferWaterMark(
                                        WRITE_LOW_WATER_MARK, WRITE_HIGH_WATER_MARK));
                                accepted.add(channel);
                            }
                        }).bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                        .sync().channel();
                server = bound;
                var address = (InetSocketAddress) server.localAddress();
                firstPeer.setReceiveBufferSize(SOCKET_RECEIVE_BYTES);
                firstPeer.setSendBufferSize(CHUNK_BYTES);
                firstPeer.setSoTimeout((int) TIMEOUT.toMillis());
                firstPeer.connect(address, (int) TIMEOUT.toMillis());
                first = accepted.poll(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                if (first == null) throw new AssertionError("first relay socket was not accepted");

                secondPeer.setReceiveBufferSize(SOCKET_RECEIVE_BYTES);
                secondPeer.setSoTimeout((int) TIMEOUT.toMillis());
                secondPeer.connect(address, (int) TIMEOUT.toMillis());
                second = accepted.poll(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                if (second == null) throw new AssertionError("second relay socket was not accepted");
                firstRelay = first;
                secondRelay = second;
                await(() -> firstRelay.isActive() && secondRelay.isActive(), "accepted sockets became active");
            } catch (Throwable failure) {
                if (bound != null) bound.close().syncUninterruptibly();
                if (first != null) first.close().syncUninterruptibly();
                if (second != null) second.close().syncUninterruptibly();
                closeQuietly(firstPeer);
                closeQuietly(secondPeer);
                shutdown(io);
                shutdown(boss);
                workers.close();
                throw failure;
            }
        }

        @Override public void close() throws Exception {
            firstRelay.close().syncUninterruptibly();
            secondRelay.close().syncUninterruptibly();
            server.close().syncUninterruptibly();
            closeQuietly(firstPeer);
            closeQuietly(secondPeer);
            shutdown(io);
            shutdown(boss);
            workers.close();
        }
    }

    private static void closeQuietly(Socket socket) {
        try { socket.close(); } catch (Exception ignored) { }
    }

    private static void shutdown(NioEventLoopGroup group) {
        Future<?> stopped = group.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        assertTrue(stopped.awaitUninterruptibly(TIMEOUT.toMillis()), "event loop shutdown timed out");
    }
}

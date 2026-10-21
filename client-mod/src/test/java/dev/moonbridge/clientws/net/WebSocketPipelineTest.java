package dev.moonbridge.clientws.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.handler.ssl.SslHandler;

class WebSocketPipelineTest {

    private static NioEventLoopGroup group;

    @BeforeAll
    static void start() {
        group = new NioEventLoopGroup(2);
    }

    @AfterAll
    static void stop() {
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS)
            .syncUninterruptibly();
    }

    /** Connects like vanilla's provideLanClient plus our initializer hook: vanilla handlers first, then install. */
    private static Channel connect(int port, final WebSocketAddress address, final List<byte[]> writeOnActive,
        final AtomicReference<VanillaStub> stub) {
        return new Bootstrap().group(group)
            .channel(NioSocketChannel.class)
            .handler(new ChannelInitializer<Channel>() {

                @Override
                protected void initChannel(Channel ch) {
                    stub.set(VanillaStub.installInto(ch.pipeline(), writeOnActive));
                    WebSocketPipeline.install(ch.pipeline(), address);
                }
            })
            .connect(InetAddress.getLoopbackAddress(), port)
            .syncUninterruptibly()
            .channel();
    }

    private static byte[] randomBytes(Random r, int n) {
        byte[] b = new byte[n];
        r.nextBytes(b);
        return b;
    }

    private static byte[] concat(List<byte[]> parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            o.write(p, 0, p.length);
        }
        return o.toByteArray();
    }

    @Test
    void echoesThroughNettyWebSocketServerWithArbitraryChunking() throws Exception {
        final ByteArrayOutputStream serverGot = new ByteArrayOutputStream();
        final List<Integer> serverFrameSizes = Collections.synchronizedList(new ArrayList<Integer>());
        final AtomicReference<String> path = new AtomicReference<String>();
        final AtomicReference<String> host = new AtomicReference<String>();
        final Random random = new Random(42);

        Channel server = new ServerBootstrap().group(group)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<Channel>() {

                @Override
                protected void initChannel(Channel ch) {
                    ch.pipeline()
                        .addLast(
                            new HttpServerCodec(),
                            new HttpObjectAggregator(65536),
                            new SimpleChannelInboundHandler<Object>() {

                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
                                    if (msg instanceof FullHttpRequest) {
                                        FullHttpRequest req = (FullHttpRequest) msg;
                                        path.set(req.getUri());
                                        host.set(
                                            req.headers()
                                                .get("Host"));
                                        WebSocketServerHandshaker hs = new WebSocketServerHandshakerFactory(
                                            "ws://localhost/mc",
                                            null,
                                            false,
                                            1 << 20).newHandshaker(req);
                                        hs.handshake(ctx.channel(), req);
                                    } else if (msg instanceof BinaryWebSocketFrame
                                        || msg instanceof ContinuationWebSocketFrame) {
                                            ByteBuf c = ((WebSocketFrame) msg).content();
                                            byte[] b = new byte[c.readableBytes()];
                                            c.readBytes(b);
                                            synchronized (serverGot) {
                                                serverGot.write(b, 0, b.length);
                                            }
                                            serverFrameSizes.add(b.length);
                                            // Echo re-chunked: 1-byte first fragment, then the rest as a continuation,
                                            // plus a separate message for good measure.
                                            if (b.length >= 3) {
                                                ctx.write(
                                                    new BinaryWebSocketFrame(
                                                        false,
                                                        0,
                                                        Unpooled.wrappedBuffer(b, 0, 1)));
                                                ctx.write(
                                                    new ContinuationWebSocketFrame(
                                                        true,
                                                        0,
                                                        Unpooled.wrappedBuffer(b, 1, 1)));
                                                ctx.writeAndFlush(
                                                    new BinaryWebSocketFrame(
                                                        Unpooled.wrappedBuffer(b, 2, b.length - 2)));
                                            } else {
                                                ctx.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(b)));
                                            }
                                        }
                                }
                            });
                }
            })
            .bind(InetAddress.getLoopbackAddress(), 0)
            .syncUninterruptibly()
            .channel();
        int port = ((java.net.InetSocketAddress) server.localAddress()).getPort();
        try {
            List<byte[]> early = Arrays
                .asList(randomBytes(random, 5), randomBytes(random, 1), randomBytes(random, 300));
            AtomicReference<VanillaStub> stub = new AtomicReference<VanillaStub>();
            WebSocketAddress address = WebSocketAddress.parse("ws://127.0.0.1:" + port + "/mc?x=1");
            Channel client = connect(port, address, early, stub);

            byte[] later = randomBytes(random, 70000); // > 64 KiB: exercises the 64-bit length form
            client.writeAndFlush(Unpooled.wrappedBuffer(later))
                .syncUninterruptibly();

            List<byte[]> all = new ArrayList<byte[]>(early);
            all.add(later);
            byte[] expected = concat(all);
            assertArrayEquals(
                expected,
                stub.get()
                    .awaitBytes(expected.length));
            synchronized (serverGot) {
                assertArrayEquals(expected, serverGot.toByteArray());
            }
            assertEquals(Arrays.asList(5, 1, 300, 70000), serverFrameSizes, "one binary frame per write");
            assertEquals("/mc?x=1", path.get());
            assertEquals("127.0.0.1:" + port, host.get());

            // After the upgrade only our frame handlers remain in front of the unchanged vanilla handlers.
            List<String> names = client.pipeline()
                .names();
            assertEquals(
                Arrays.asList(
                    WebSocketPipeline.FRAME_DECODER,
                    WebSocketPipeline.FRAME_ENCODER,
                    WebSocketPipeline.FRAMES,
                    "timeout",
                    "splitter",
                    "decoder",
                    "prepender",
                    "encoder",
                    "packet_handler"),
                names.subList(0, 9));
            assertTrue(stub.get().exceptions.isEmpty(), String.valueOf(stub.get().exceptions));
            client.close()
                .syncUninterruptibly();
        } finally {
            server.close()
                .syncUninterruptibly();
        }
    }

    @Test
    void holdsWritesUntil101AndDeliversBytesSentWithTheResponse() throws Exception {
        try (RawWsServer server = new RawWsServer()) {
            byte[] early = "handshake-packet".getBytes(StandardCharsets.US_ASCII);
            AtomicReference<VanillaStub> stub = new AtomicReference<VanillaStub>();
            WebSocketAddress address = WebSocketAddress.parse("ws://mc.example.com:" + server.port() + "/mc");
            Channel client = connect(server.port(), address, Collections.singletonList(early), stub);

            String head = server.acceptAndReadRequest();
            assertTrue(head.startsWith("GET /mc HTTP/1.1\r\n"), head);
            assertEquals("mc.example.com:" + server.port(), server.header("Host"));
            assertEquals("websocket", server.header("Upgrade"));
            assertEquals("13", server.header("Sec-WebSocket-Version"));

            // Nothing but the request may arrive before the 101.
            server.socket.setSoTimeout(300);
            try {
                int b = server.input()
                    .read();
                throw new AssertionError("client sent data before the handshake completed: " + b);
            } catch (SocketTimeoutException expected) {
                // good
            }
            server.socket.setSoTimeout(10000);

            byte[] fromServer = "login-success".getBytes(StandardCharsets.US_ASCII);
            server.send(server.switchingProtocols(), RawWsServer.frame(0x2, true, fromServer));

            Object[] frame = server.readFrame();
            assertEquals(0x82, frame[0]);
            assertArrayEquals(early, (byte[]) frame[1]);
            assertArrayEquals(
                fromServer,
                stub.get()
                    .awaitBytes(fromServer.length));
            // The handler removes itself at the end of the read that completed the upgrade; ask the event loop so
            // the check runs after that read.
            final Channel c = client;
            assertNull(
                client.eventLoop()
                    .submit(
                        () -> c.pipeline()
                            .get(WebSocketPipeline.HANDSHAKE))
                    .get(10, TimeUnit.SECONDS));
            client.close()
                .syncUninterruptibly();
        }
    }

    @Test
    void textFrameClosesTheConnection() throws Exception {
        try (RawWsServer server = new RawWsServer()) {
            AtomicReference<VanillaStub> stub = new AtomicReference<VanillaStub>();
            Channel client = connect(
                server.port(),
                WebSocketAddress.parse("ws://127.0.0.1:" + server.port()),
                Collections.<byte[]>emptyList(),
                stub);
            server.acceptAndReadRequest();
            server.send(
                server.switchingProtocols(),
                RawWsServer.frame(0x1, true, "hi".getBytes(StandardCharsets.US_ASCII)));
            Object[] close = server.readFrame();
            assertEquals(0x88, close[0]);
            assertTrue(stub.get().inactive.await(10, TimeUnit.SECONDS));
            assertFalse(client.isOpen());
            assertTrue(
                stub.get().exceptions.get(0)
                    .getMessage()
                    .contains("text frame"));
        }
    }

    @Test
    void pingIsAnsweredAndCloseFrameCloses() throws Exception {
        try (RawWsServer server = new RawWsServer()) {
            AtomicReference<VanillaStub> stub = new AtomicReference<VanillaStub>();
            Channel client = connect(
                server.port(),
                WebSocketAddress.parse("ws://127.0.0.1:" + server.port()),
                Collections.<byte[]>emptyList(),
                stub);
            server.acceptAndReadRequest();
            // A fragmented binary message with a ping in between, then close.
            server.send(
                server.switchingProtocols(),
                RawWsServer.frame(0x2, false, new byte[] { 1, 2 }),
                RawWsServer.frame(0x9, true, new byte[] { 'p' }),
                RawWsServer.frame(0x0, true, new byte[] { 3 }));
            Object[] pong = server.readFrame();
            assertEquals(0x8A, pong[0]);
            assertArrayEquals(new byte[] { 'p' }, (byte[]) pong[1]);
            assertArrayEquals(
                new byte[] { 1, 2, 3 },
                stub.get()
                    .awaitBytes(3));

            server.send(RawWsServer.frame(0x8, true, new byte[] { 0x03, (byte) 0xE8 }));
            Object[] close = server.readFrame();
            assertEquals(0x88, close[0]);
            assertTrue(stub.get().inactive.await(10, TimeUnit.SECONDS));
            assertFalse(client.isOpen());
            assertTrue(stub.get().exceptions.isEmpty(), String.valueOf(stub.get().exceptions));
        }
    }

    @Test
    void rejectedUpgradeFailsWithReasonAndPendingWrites() throws Exception {
        try (RawWsServer server = new RawWsServer()) {
            AtomicReference<VanillaStub> stub = new AtomicReference<VanillaStub>();
            Channel client = connect(
                server.port(),
                WebSocketAddress.parse("ws://127.0.0.1:" + server.port() + "/wrong"),
                Collections.<byte[]>emptyList(),
                stub);
            server.acceptAndReadRequest();
            final CountDownLatch failed = new CountDownLatch(1);
            client.writeAndFlush(Unpooled.wrappedBuffer(new byte[] { 1 }))
                .addListener(f -> {
                    if (!f.isSuccess()) {
                        failed.countDown();
                    }
                });
            server.send("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            assertTrue(stub.get().inactive.await(10, TimeUnit.SECONDS));
            assertTrue(failed.await(10, TimeUnit.SECONDS), "held write must fail, not hang");
            String message = stub.get().exceptions.get(0)
                .getMessage();
            assertTrue(message.contains("403"), message);
        }
    }

    @Test
    void wrongAcceptKeyIsRejected() throws Exception {
        try (RawWsServer server = new RawWsServer()) {
            AtomicReference<VanillaStub> stub = new AtomicReference<VanillaStub>();
            connect(
                server.port(),
                WebSocketAddress.parse("ws://127.0.0.1:" + server.port()),
                Collections.<byte[]>emptyList(),
                stub);
            server.acceptAndReadRequest();
            server.send(
                ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: AAAAAAAAAAAAAAAAAAAAAAAAAAA=\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
            assertTrue(stub.get().inactive.await(10, TimeUnit.SECONDS));
            assertTrue(
                stub.get().exceptions.get(0)
                    .getMessage()
                    .contains("Sec-WebSocket-Accept"));
        }
    }

    @Test
    void wssInstallsSslHandlerWithSniAndHostnameVerification() throws Exception {
        // Registered but never connected: inspects the layout without starting TLS.
        Channel ch = new NioSocketChannel();
        group.register(ch)
            .syncUninterruptibly();
        VanillaStub.installInto(ch.pipeline(), Collections.<byte[]>emptyList());
        WebSocketPipeline
            .install(ch.pipeline(), WebSocketAddress.parse("wss://mc.example.com/mc"), SSLContext.getDefault());
        List<String> names = ch.pipeline()
            .names();
        assertEquals(
            Arrays.asList(
                WebSocketPipeline.SSL,
                WebSocketPipeline.HANDSHAKE,
                WebSocketPipeline.FRAME_DECODER,
                WebSocketPipeline.FRAME_ENCODER,
                WebSocketPipeline.FRAMES,
                "timeout"),
            names.subList(0, 6));
        SSLEngine engine = ((SslHandler) ch.pipeline()
            .get(WebSocketPipeline.SSL)).engine();
        assertTrue(engine.getUseClientMode());
        assertEquals(
            "HTTPS",
            engine.getSSLParameters()
                .getEndpointIdentificationAlgorithm());
        assertEquals(
            Collections.singletonList(new SNIHostName("mc.example.com")),
            engine.getSSLParameters()
                .getServerNames());
        assertEquals("mc.example.com", engine.getPeerHost());
        assertEquals(443, engine.getPeerPort());
        // Netty 4.0.10's SslHandler hangs in a TLS 1.3 handshake, so TLS 1.3 must never be offered.
        assertEquals(Collections.singletonList("TLSv1.2"), Arrays.asList(engine.getEnabledProtocols()));
        ch.close();

        SSLEngine literal = WebSocketPipeline
            .newClientEngine(SSLContext.getDefault(), WebSocketAddress.parse("wss://1.2.3.4:8443/"));
        assertEquals(
            "HTTPS",
            literal.getSSLParameters()
                .getEndpointIdentificationAlgorithm());
    }

    @Test
    void plainWsHasNoSslHandler() {
        // Registered but never connected: inspects the layout without starting TLS.
        Channel ch = new NioSocketChannel();
        group.register(ch)
            .syncUninterruptibly();
        VanillaStub.installInto(ch.pipeline(), Collections.<byte[]>emptyList());
        WebSocketPipeline.install(ch.pipeline(), WebSocketAddress.parse("ws://h/"));
        assertNull(
            ch.pipeline()
                .get(WebSocketPipeline.SSL));
        assertEquals(
            WebSocketPipeline.HANDSHAKE,
            ch.pipeline()
                .names()
                .get(0));
        ch.close();
    }
}

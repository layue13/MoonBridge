package dev.moonbridge.core.net;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.socket.SocketChannel;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NetworkTransportTest {
    @Test
    void autoPrefersTheNativeTransportOfThisPlatform() {
        String expected = Epoll.isAvailable() ? "epoll" : KQueue.isAvailable() ? "kqueue" : "nio";
        assertEquals(expected, NetworkTransport.select(NetworkTransport.Preference.AUTO).name());
        assertEquals("nio", NetworkTransport.select(NetworkTransport.Preference.NIO).name());
    }

    @Test
    void explicitUnavailableTransportFailsInsteadOfFallingBack() {
        if (!Epoll.isAvailable()) {
            assertThrows(IllegalStateException.class, () -> NetworkTransport.select(NetworkTransport.Preference.EPOLL));
        }
        if (!KQueue.isAvailable()) {
            assertThrows(IllegalStateException.class, () -> NetworkTransport.select(NetworkTransport.Preference.KQUEUE));
        }
    }

    @Test
    void eventLoopsQueueTasksInANonBlockingMpscQueue() throws Exception {
        for (var preference : new NetworkTransport.Preference[] {
                NetworkTransport.Preference.AUTO, NetworkTransport.Preference.NIO}) {
            var group = NetworkTransport.select(preference).newEventLoopGroup(1, Thread.ofPlatform().daemon().factory());
            try {
                Class<?> type = io.netty.util.concurrent.SingleThreadEventExecutor.class;
                var queue = type.getDeclaredField("taskQueue");
                queue.setAccessible(true);
                String name = queue.get(group.next()).getClass().getName();
                assertTrue(name.contains("Mpsc"), preference + " event loop task queue was " + name);
            } finally {
                group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
            }
        }
    }

    @Test
    void selectedTransportCarriesBytesOverLoopback() throws Exception {
        for (var preference : new NetworkTransport.Preference[] {
                NetworkTransport.Preference.AUTO, NetworkTransport.Preference.NIO}) {
            var transport = NetworkTransport.select(preference);
            var group = transport.newEventLoopGroup(1, Thread.ofPlatform().daemon().factory());
            try {
                var received = new CompletableFuture<Integer>();
                Channel server = new ServerBootstrap().group(group).channel(transport.serverChannel())
                        .childHandler(new ChannelInitializer<SocketChannel>() {
                            @Override protected void initChannel(SocketChannel channel) {
                                channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                    @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
                                        ByteBuf bytes = (ByteBuf) message;
                                        received.complete((int) bytes.readByte());
                                        bytes.release();
                                    }
                                });
                            }
                        }).bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).sync().channel();
                Channel client = new Bootstrap().group(group).channel(transport.socketChannel())
                        .handler(new ChannelInboundHandlerAdapter())
                        .connect(server.localAddress()).sync().channel();
                client.writeAndFlush(Unpooled.wrappedBuffer(new byte[] {42})).sync();
                assertEquals(42, received.get(5, TimeUnit.SECONDS), transport.name());
                client.close().sync();
                server.close().sync();
            } finally {
                group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
            }
        }
    }
}

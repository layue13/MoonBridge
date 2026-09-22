package dev.strataproxy.network;

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.kqueue.KQueueServerSocketChannel;
import io.netty.channel.kqueue.KQueueSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

record NettyTransport(
        String name,
        boolean nativeTransport,
        EventLoopGroup bossGroup,
        EventLoopGroup workerGroup,
        Class<? extends ServerChannel> serverChannel,
        Class<? extends Channel> clientChannel) {
    static NettyTransport select(boolean preferNative, int workerThreads) {
        if (preferNative && Epoll.isAvailable()) {
            return new NettyTransport(
                    "epoll",
                    true,
                    new EpollEventLoopGroup(1),
                    new EpollEventLoopGroup(workerThreads),
                    EpollServerSocketChannel.class,
                    EpollSocketChannel.class);
        }
        if (preferNative && KQueue.isAvailable()) {
            return new NettyTransport(
                    "kqueue",
                    true,
                    new KQueueEventLoopGroup(1),
                    new KQueueEventLoopGroup(workerThreads),
                    KQueueServerSocketChannel.class,
                    KQueueSocketChannel.class);
        }
        return new NettyTransport(
                "nio",
                false,
                new NioEventLoopGroup(1),
                new NioEventLoopGroup(workerThreads),
                NioServerSocketChannel.class,
                NioSocketChannel.class);
    }
}

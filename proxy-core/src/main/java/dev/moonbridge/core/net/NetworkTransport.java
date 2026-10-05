package dev.moonbridge.core.net;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.IoEventLoop;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SingleThreadIoEventLoop;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueDatagramChannel;
import io.netty.channel.kqueue.KQueueIoHandler;
import io.netty.channel.kqueue.KQueueServerSocketChannel;
import io.netty.channel.kqueue.KQueueSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.util.concurrent.RejectedExecutionHandlers;
import io.netty.util.internal.PlatformDependent;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.function.Supplier;

/**
 * One Netty I/O transport: the event-loop handler and the matching channel types. Channels must use
 * the transport of the event loop they are registered with, so every session socket and the DNS
 * resolver take their types from the same instance.
 */
public final class NetworkTransport {
    public enum Preference { AUTO, NIO, EPOLL, KQUEUE }

    private final String name;
    private final Supplier<IoHandlerFactory> handlers;
    private final Class<? extends ServerSocketChannel> serverChannel;
    private final Class<? extends SocketChannel> socketChannel;
    private final Class<? extends DatagramChannel> datagramChannel;

    private NetworkTransport(String name, Supplier<IoHandlerFactory> handlers,
                             Class<? extends ServerSocketChannel> serverChannel,
                             Class<? extends SocketChannel> socketChannel,
                             Class<? extends DatagramChannel> datagramChannel) {
        this.name = name;
        this.handlers = handlers;
        this.serverChannel = serverChannel;
        this.socketChannel = socketChannel;
        this.datagramChannel = datagramChannel;
    }

    public static NetworkTransport nio() {
        return new NetworkTransport("nio", NioIoHandler::newFactory, NioServerSocketChannel.class,
                NioSocketChannel.class, NioDatagramChannel.class);
    }

    /**
     * AUTO prefers epoll (Linux), then kqueue (macOS/BSD), then NIO. An explicit native preference
     * fails instead of silently falling back, so an operator's choice is never ignored.
     */
    public static NetworkTransport select(Preference preference) {
        Objects.requireNonNull(preference, "preference");
        return switch (preference) {
            case NIO -> nio();
            case EPOLL -> {
                requireAvailable("epoll", Epoll.isAvailable(), Epoll.unavailabilityCause());
                yield epoll();
            }
            case KQUEUE -> {
                requireAvailable("kqueue", KQueue.isAvailable(), KQueue.unavailabilityCause());
                yield kqueue();
            }
            case AUTO -> Epoll.isAvailable() ? epoll() : KQueue.isAvailable() ? kqueue() : nio();
        };
    }

    private static NetworkTransport epoll() {
        return new NetworkTransport("epoll", EpollIoHandler::newFactory, EpollServerSocketChannel.class,
                EpollSocketChannel.class, EpollDatagramChannel.class);
    }

    private static NetworkTransport kqueue() {
        return new NetworkTransport("kqueue", KQueueIoHandler::newFactory, KQueueServerSocketChannel.class,
                KQueueSocketChannel.class, KQueueDatagramChannel.class);
    }

    private static void requireAvailable(String name, boolean available, Throwable cause) {
        if (!available) throw new IllegalStateException(name + " transport is not available on this platform", cause);
    }

    public EventLoopGroup newEventLoopGroup(int threads, ThreadFactory threadFactory) {
        return new MultiThreadIoEventLoopGroup(threads, threadFactory, handlers.get()) {
            // A bare SingleThreadIoEventLoop queues tasks in a LinkedBlockingQueue because the generic
            // executor may block in takeTask(). I/O loops never do, which is why Netty's own NIO and epoll
            // loops use MPSC queues; the relay posts one write-completion task per frame, so this matters.
            @Override protected IoEventLoop newChild(Executor executor, IoHandlerFactory ioHandlerFactory,
                                                     Object... args) {
                return new SingleThreadIoEventLoop(this, executor, ioHandlerFactory,
                        PlatformDependent.<Runnable>newMpscQueue(), PlatformDependent.<Runnable>newMpscQueue(),
                        RejectedExecutionHandlers.reject()) { };
            }
        };
    }

    public String name() { return name; }
    public Class<? extends ServerSocketChannel> serverChannel() { return serverChannel; }
    public Class<? extends SocketChannel> socketChannel() { return socketChannel; }
    public Class<? extends DatagramChannel> datagramChannel() { return datagramChannel; }

    @Override public String toString() { return name; }
}

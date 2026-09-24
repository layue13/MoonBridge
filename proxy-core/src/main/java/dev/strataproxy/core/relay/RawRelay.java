package dev.strataproxy.core.relay;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Relays already negotiated protocol bytes without copying payload buffers. */
public final class RawRelay {
    private RawRelay() {
    }

    /** Installs paused relay handlers. Call {@link Link#start()} after removing login codecs. */
    public static Link attach(Channel client, Channel backend) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(backend, "backend");
        if (client == backend || !client.isActive() || !backend.isActive()) {
            throw new IllegalArgumentException("two distinct active channels are required");
        }
        var fromClient = new Side(backend);
        var fromBackend = new Side(client);
        fromClient.opposite = fromBackend;
        fromBackend.opposite = fromClient;
        var link = new Link(fromClient, fromBackend);
        install(client, fromClient, link);
        install(backend, fromBackend, link);
        return link;
    }

    public static final class Link {
        private final Side fromClient;
        private final Side fromBackend;
        private final AtomicInteger pending = new AtomicInteger(2);
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean readsStarted = new AtomicBoolean();
        private final CompletableFuture<Void> ready = new CompletableFuture<>();

        private Link(Side fromClient, Side fromBackend) {
            this.fromClient = fromClient;
            this.fromBackend = fromBackend;
        }

        /** Completes when both handlers are installed, before reads begin. */
        public CompletionStage<Void> ready() {
            return ready;
        }

        public void start() {
            started.set(true);
            maybeStartReads();
        }

        private void maybeStartReads() {
            if (pending.get() == 0 && started.get() && readsStarted.compareAndSet(false, true)) {
                fromClient.requestRead();
                fromBackend.requestRead();
            }
        }
    }

    private static void install(Channel channel, Side side, Link link) {
        channel.eventLoop().execute(() -> {
            if (!channel.isActive()) {
                side.peer.close();
                link.ready.completeExceptionally(new IllegalStateException("relay channel closed during installation"));
                return;
            }
            try {
                channel.config().setAutoRead(false);
                channel.pipeline().addLast("raw-relay", side);
                if (link.pending.decrementAndGet() == 0) {
                    link.ready.complete(null);
                    link.maybeStartReads();
                }
            } catch (RuntimeException failure) {
                channel.close();
                side.peer.close();
                link.ready.completeExceptionally(failure);
            }
        });
    }

    private static final class Side extends ChannelInboundHandlerAdapter {
        private final Channel peer;
        private ChannelHandlerContext context;
        private Side opposite;
        private int writesInFlight;
        private boolean closed;

        private Side(Channel peer) {
            this.peer = peer;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            context = ctx;
        }

        private void requestRead() {
            var ctx = context;
            if (ctx == null) return;
            if (!ctx.executor().inEventLoop()) {
                ctx.executor().execute(this::requestRead);
                return;
            }
            if (!closed && writesInFlight == 0 && ctx.channel().isActive() && peer.isActive() && peer.isWritable()) {
                ctx.read();
            }
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object message) {
            if (!(message instanceof ByteBuf) || closed || !peer.isActive()) {
                ReferenceCountUtil.release(message);
                closePair(ctx);
                return;
            }
            writesInFlight++;
            try {
                peer.writeAndFlush(message).addListener((ChannelFutureListener) future ->
                        ctx.executor().execute(() -> {
                            writesInFlight--;
                            if (future.isSuccess()) requestRead();
                            else closePair(ctx);
                        }));
            } catch (RuntimeException failure) {
                writesInFlight--;
                ReferenceCountUtil.release(message);
                closePair(ctx);
            }
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) {
            if (ctx.channel().isWritable()) opposite.requestRead();
            ctx.fireChannelWritabilityChanged();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            closePair(ctx);
            ctx.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            closePair(ctx);
        }

        private void closePair(ChannelHandlerContext ctx) {
            if (closed) return;
            closed = true;
            ctx.channel().close();
            peer.close();
        }
    }
}

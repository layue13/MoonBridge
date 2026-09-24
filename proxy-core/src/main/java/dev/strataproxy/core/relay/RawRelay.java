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

        /** Stops forwarding and waits for writes already accepted by the old peer. */
        public CompletionStage<Void> pause() {
            return ready.thenCompose(ignored -> CompletableFuture.allOf(fromClient.pause(), fromBackend.pause()));
        }

        /** Resumes the same pair after a replacement attempt fails. */
        public CompletionStage<Void> resume() {
            return ready.thenCompose(ignored -> CompletableFuture.allOf(fromClient.resume(), fromBackend.resume()));
        }

        /** Removes both paused handlers without closing either channel. */
        public CompletionStage<Void> detach() {
            return pause().thenCompose(ignored ->
                    CompletableFuture.allOf(fromClient.detach(), fromBackend.detach()));
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
        private boolean paused;
        private boolean detached;
        private CompletableFuture<Void> pauseComplete;

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
            if (!closed && !paused && !detached && writesInFlight == 0
                    && ctx.channel().isActive() && peer.isActive() && peer.isWritable()) {
                ctx.read();
            }
        }

        private CompletableFuture<Void> pause() {
            CompletableFuture<Void> result = new CompletableFuture<>();
            context.executor().execute(() -> {
                if (closed || detached || !context.channel().isActive()) {
                    result.completeExceptionally(new IllegalStateException("relay is closed"));
                    return;
                }
                if (paused && pauseComplete != null) {
                    pauseComplete.whenComplete((ignored, failure) -> {
                        if (failure == null) result.complete(null);
                        else result.completeExceptionally(failure);
                    });
                    return;
                }
                paused = true;
                context.channel().config().setAutoRead(false);
                if (writesInFlight == 0) result.complete(null);
                else pauseComplete = result;
            });
            return result;
        }

        private CompletableFuture<Void> resume() {
            CompletableFuture<Void> result = new CompletableFuture<>();
            context.executor().execute(() -> {
                if (closed || detached || writesInFlight != 0 || !context.channel().isActive()) {
                    result.completeExceptionally(new IllegalStateException("relay is not ready to resume"));
                    return;
                }
                paused = false;
                pauseComplete = null;
                requestRead();
                result.complete(null);
            });
            return result;
        }

        private CompletableFuture<Void> detach() {
            CompletableFuture<Void> result = new CompletableFuture<>();
            context.executor().execute(() -> {
                if (!paused || writesInFlight != 0 || detached) {
                    result.completeExceptionally(new IllegalStateException("relay is not paused"));
                    return;
                }
                detached = true;
                try {
                    context.pipeline().remove(this);
                    result.complete(null);
                } catch (RuntimeException failure) {
                    result.completeExceptionally(failure);
                }
            });
            return result;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object message) {
            if (paused || detached) {
                ReferenceCountUtil.release(message);
                return;
            }
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
                            if (paused && writesInFlight == 0 && pauseComplete != null) {
                                if (future.isSuccess()) pauseComplete.complete(null);
                                else pauseComplete.completeExceptionally(future.cause());
                                pauseComplete = null;
                            }
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
            if (!detached) closePair(ctx);
            ctx.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            closePair(ctx);
        }

        private void closePair(ChannelHandlerContext ctx) {
            if (closed) return;
            closed = true;
            if (pauseComplete != null) {
                pauseComplete.completeExceptionally(new IllegalStateException("relay closed while pausing"));
                pauseComplete = null;
            }
            ctx.channel().close();
            peer.close();
        }
    }
}

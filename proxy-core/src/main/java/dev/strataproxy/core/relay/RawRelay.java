package dev.strataproxy.core.relay;

import dev.strataproxy.core.protocol.ProtocolProfile;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Relays already negotiated protocol bytes without copying payload buffers. */
public final class RawRelay {
    // Per direction: allow one maximum protocol 5 frame, including its length prefix.
    static final int MAX_PAUSED_BYTES = ProtocolProfile.minecraft1710().maxFrameBytes() + 3;
    static final int MAX_PAUSED_MESSAGES = 1024;

    private RawRelay() {
    }

    /** Installs paused relay handlers. Call {@link Link#start()} after removing login codecs. */
    public static Link attach(Channel client, Channel backend) {
        return attach(client, backend, null, null);
    }

    /** Observers receive borrowed duplicates before each raw buffer is forwarded. */
    public static Link attach(Channel client, Channel backend, Consumer<ByteBuf> clientObserver,
                              Consumer<ByteBuf> backendObserver) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(backend, "backend");
        if (client == backend || !client.isActive() || !backend.isActive()) {
            throw new IllegalArgumentException("two distinct active channels are required");
        }
        var fromClient = new Side(backend, clientObserver);
        var fromBackend = new Side(client, backendObserver);
        fromClient.opposite = fromBackend;
        fromBackend.opposite = fromClient;
        var link = new Link(fromClient, fromBackend);
        fromClient.link = link;
        fromBackend.link = link;
        install(client, fromClient, link);
        install(backend, fromBackend, link);
        return link;
    }

    /** Requests the next manual read when an upstream protocol handler consumed a frame. */
    public static void continueAfterDrop(Channel source) {
        if (source.pipeline().get("raw-relay") instanceof Side side) side.requestRead();
    }

    public static final class Link {
        private final Side fromClient;
        private final Side fromBackend;
        private final AtomicInteger pending = new AtomicInteger(2);
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean readsStarted = new AtomicBoolean();
        private final CompletableFuture<Void> ready = new CompletableFuture<>();
        private final Object detachLock = new Object();

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

        /**
         * Removes both paused handlers if neither side has queued reads; otherwise leaves the link resumable.
         * Both channels keep auto-read disabled so a replacement relay can take over without reading unowned bytes.
         */
        public CompletionStage<Void> detach() {
            return pause().thenCompose(ignored -> {
                synchronized (detachLock) {
                    if (!fromClient.isDetachable() || !fromBackend.isDetachable()) {
                        return CompletableFuture.failedFuture(
                                new IllegalStateException("relay has queued messages to resume"));
                    }
                    fromClient.detached = true;
                    fromBackend.detached = true;
                }
                return CompletableFuture.allOf(fromClient.remove(), fromBackend.remove());
            });
        }

        /** Removes temporary protocol observers; the ordinary relay path then forwards without inspecting buffers. */
        public void stopObserving() {
            ready.thenRun(() -> {
                fromClient.stopObserving();
                fromBackend.stopObserving();
            });
        }

        private void maybeStartReads() {
            if (pending.get() == 0 && started.get() && readsStarted.compareAndSet(false, true)) {
                fromClient.enableReads();
                fromBackend.enableReads();
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
        private final ArrayDeque<ByteBuf> pausedMessages = new ArrayDeque<>();
        private Consumer<ByteBuf> observer;
        private ChannelHandlerContext context;
        private Side opposite;
        private Link link;
        private int writesInFlight;
        private int pausedBytes;
        private volatile boolean closed;
        private boolean paused = true;
        private volatile boolean detached;
        private boolean readsEnabled;
        private CompletableFuture<Void> pauseComplete;
        private CompletableFuture<Void> resumeComplete;

        private Side(Channel peer, Consumer<ByteBuf> observer) {
            this.peer = peer;
            this.observer = observer;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            context = ctx;
        }

        private void enableReads() {
            var ctx = context;
            if (ctx == null) return;
            if (!ctx.executor().inEventLoop()) {
                ctx.executor().execute(this::enableReads);
                return;
            }
            readsEnabled = true;
            // An in-flight read may arrive after attach but before start removes the
            // login codecs. Flush those owned buffers before requesting new data.
            if (paused) {
                resume().whenComplete((ignored, failure) -> {
                    if (failure != null) closePair(context);
                });
            } else {
                requestRead();
            }
        }

        private void requestRead() {
            var ctx = context;
            if (ctx == null) return;
            if (!ctx.executor().inEventLoop()) {
                ctx.executor().execute(this::requestRead);
                return;
            }
            if (readsEnabled && !closed && !paused && !detached && writesInFlight == 0
                    && ctx.channel().isActive() && peer.isActive() && peer.isWritable()) {
                ctx.read();
            }
        }

        private void stopObserving() {
            context.executor().execute(() -> observer = null);
        }

        private CompletableFuture<Void> pause() {
            CompletableFuture<Void> result = new CompletableFuture<>();
            context.executor().execute(() -> {
                if (closed || detached || !context.channel().isActive()) {
                    result.completeExceptionally(new IllegalStateException("relay is closed"));
                    return;
                }
                if (resumeComplete != null) {
                    // A replay owns the queued buffers until its last write completes.
                    resumeComplete.whenComplete((ignored, failure) -> {
                        if (failure != null) result.completeExceptionally(failure);
                        else pause().whenComplete((unused, pauseFailure) -> completeFrom(result, pauseFailure));
                    });
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
                if (resumeComplete != null) {
                    resumeComplete.whenComplete((ignored, failure) -> completeFrom(result, failure));
                    return;
                }
                synchronized (link.detachLock) {
                    if (closed || detached || writesInFlight != 0 || !context.channel().isActive() || !paused) {
                        result.completeExceptionally(new IllegalStateException("relay is not ready to resume"));
                        return;
                    }
                    resumeComplete = result;
                }
                drainPausedMessages();
            });
            return result;
        }

        private CompletableFuture<Void> remove() {
            CompletableFuture<Void> result = new CompletableFuture<>();
            context.executor().execute(() -> {
                try {
                    context.pipeline().remove(this);
                    result.complete(null);
                } catch (RuntimeException failure) {
                    result.completeExceptionally(failure);
                }
            });
            return result;
        }

        /** Called with the link lock held after both directions finish pausing. */
        private boolean isDetachable() {
            return paused && writesInFlight == 0 && !detached && !closed
                    && resumeComplete == null && context.channel().isActive() && pausedMessages.isEmpty();
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object message) {
            if (detached) {
                ctx.fireChannelRead(message);
                return;
            }
            if (closed) {
                ReferenceCountUtil.release(message);
                return;
            }
            if (!(message instanceof ByteBuf)) {
                ReferenceCountUtil.release(message);
                closePair(ctx);
                return;
            }
            if (paused) {
                ByteBuf bytes = (ByteBuf) message;
                int readable = bytes.readableBytes();
                boolean overflow;
                boolean forward;
                synchronized (link.detachLock) {
                    forward = detached;
                    overflow = !forward && (pausedMessages.size() >= MAX_PAUSED_MESSAGES
                            || readable > MAX_PAUSED_BYTES - pausedBytes);
                    if (!forward && !overflow) {
                        pausedMessages.addLast(bytes);
                        pausedBytes += readable;
                    }
                }
                if (forward) {
                    ctx.fireChannelRead(message);
                    return;
                }
                if (overflow) {
                    ReferenceCountUtil.release(message);
                    closePair(ctx);
                }
                return;
            }
            if (!peer.isActive()) {
                ReferenceCountUtil.release(message);
                closePair(ctx);
                return;
            }
            writesInFlight++;
            try {
                Consumer<ByteBuf> activeObserver = observer;
                if (activeObserver != null) activeObserver.accept(((ByteBuf) message).duplicate());
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
            releasePausedMessages();
            if (!detached) closePair(ctx);
            ctx.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            closePair(ctx);
        }

        private void closePair(ChannelHandlerContext ctx) {
            synchronized (link.detachLock) {
                if (closed) return;
                closed = true;
            }
            releasePausedMessages();
            if (pauseComplete != null) {
                pauseComplete.completeExceptionally(new IllegalStateException("relay closed while pausing"));
                pauseComplete = null;
            }
            if (resumeComplete != null) {
                resumeComplete.completeExceptionally(new IllegalStateException("relay closed while resuming"));
                resumeComplete = null;
            }
            ctx.channel().close();
            peer.close();
        }

        private void drainPausedMessages() {
            if (closed || detached || resumeComplete == null) return;
            if (!peer.isActive()) {
                failResume(new IllegalStateException("relay peer closed while resuming"));
                return;
            }
            ByteBuf next;
            synchronized (link.detachLock) {
                next = pausedMessages.pollFirst();
                if (next == null) pausedBytes = 0;
                else pausedBytes -= next.readableBytes();
            }
            if (next == null) {
                paused = false;
                pauseComplete = null;
                CompletableFuture<Void> completed = resumeComplete;
                resumeComplete = null;
                requestRead();
                completed.complete(null);
                return;
            }
            writesInFlight++;
            try {
                Consumer<ByteBuf> activeObserver = observer;
                if (activeObserver != null) activeObserver.accept(next.duplicate());
                peer.writeAndFlush(next).addListener((ChannelFutureListener) future ->
                        context.executor().execute(() -> {
                            writesInFlight--;
                            if (!future.isSuccess()) {
                                failResume(future.cause());
                                return;
                            }
                            drainPausedMessages();
                        }));
            } catch (RuntimeException failure) {
                writesInFlight--;
                next.release();
                failResume(failure);
            }
        }

        private void failResume(Throwable failure) {
            CompletableFuture<Void> result = resumeComplete;
            resumeComplete = null;
            if (result != null) result.completeExceptionally(failure);
            closePair(context);
        }

        private void releasePausedMessages() {
            synchronized (link.detachLock) {
                ByteBuf bytes;
                while ((bytes = pausedMessages.pollFirst()) != null) ReferenceCountUtil.release(bytes);
                pausedBytes = 0;
            }
        }

        private static void completeFrom(CompletableFuture<Void> target, Throwable failure) {
            if (failure == null) target.complete(null);
            else target.completeExceptionally(failure);
        }
    }
}

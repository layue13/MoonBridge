package dev.strataproxy.core.session;

import dev.strataproxy.api.AccessDecision;
import dev.strataproxy.api.event.ConnectionAdmissionEvent;
import dev.strataproxy.core.event.EventDispatcher;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** One-shot TCP admission gate, removed before protocol parsing or normal relay. */
final class ConnectionGate extends ChannelInboundHandlerAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectionGate.class);
    private static final int MAX_PENDING_BYTES = 4096;
    private final EventDispatcher events;
    private final Duration timeout;
    private final Runnable accepted;
    private final Runnable closeSession;
    private CompletableFuture<AccessDecision> request;
    private ScheduledFuture<?> deadline;
    private ByteBuf pending;
    private boolean finished;

    ConnectionGate(EventDispatcher events, Duration timeout, Runnable accepted, Runnable closeSession) {
        this.events = events;
        this.timeout = timeout;
        this.accepted = accepted;
        this.closeSession = closeSession;
    }

    @Override public void channelActive(ChannelHandlerContext ctx) {
        try {
            deadline = ctx.executor().schedule(this::deny, timeout.toNanos(), TimeUnit.NANOSECONDS);
            request = Objects.requireNonNull(events.dispatch(new ConnectionAdmissionEvent((InetSocketAddress) ctx.channel().remoteAddress())),
                    "connection check stage").toCompletableFuture();
            request.whenComplete((decision, failure) -> {
                try {
                    ctx.executor().execute(() -> {
                        if (finished) return;
                        if (failure != null || !(decision instanceof AccessDecision.Allowed) || !ctx.channel().isActive()) {
                            if (failure != null) LOGGER.debug("Connection access check failed", failure);
                            deny();
                            return;
                        }
                        finished = true;
                        ByteBuf buffered = pending;
                        pending = null;
                        try {
                            accepted.run();
                            ctx.pipeline().remove(this);
                            if (buffered != null) {
                                ByteBuf replay = buffered;
                                buffered = null;
                                ctx.fireChannelRead(replay);
                                ctx.fireChannelReadComplete();
                            }
                        } catch (RuntimeException transitionFailure) {
                            LOGGER.debug("Could not finish connection access check", transitionFailure);
                            deny();
                        } finally {
                            ReferenceCountUtil.release(buffered);
                        }
                    });
                } catch (RejectedExecutionException shutdown) {
                    closeSession.run();
                }
            });
        } catch (RuntimeException failure) {
            LOGGER.debug("Could not start connection access check", failure);
            deny();
        }
        ctx.fireChannelActive();
    }

    @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
        try {
            if (finished) return;
            if (!(message instanceof ByteBuf bytes)
                    || bytes.readableBytes() > MAX_PENDING_BYTES - (pending == null ? 0 : pending.readableBytes())) {
                deny();
                return;
            }
            if (pending == null) pending = ctx.alloc().buffer(128, MAX_PENDING_BYTES);
            pending.writeBytes(bytes, bytes.readerIndex(), bytes.readableBytes());
        } finally {
            ReferenceCountUtil.release(message);
        }
    }

    private void deny() {
        cleanup();
        closeSession.run();
    }

    private void cleanup() {
        finished = true;
        if (deadline != null) deadline.cancel(false);
        if (request != null && !request.isDone()) request.cancel(false);
        ReferenceCountUtil.release(pending);
        pending = null;
    }

    @Override public void channelInactive(ChannelHandlerContext ctx) {
        cleanup();
        ctx.fireChannelInactive();
    }

    @Override public void handlerRemoved(ChannelHandlerContext ctx) { cleanup(); }

    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { deny(); }
}

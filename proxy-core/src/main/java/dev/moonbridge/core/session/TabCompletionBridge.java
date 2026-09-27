package dev.moonbridge.core.session;

import dev.moonbridge.core.protocol.MinecraftTabCompletion;
import dev.moonbridge.core.relay.RawRelay;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** One backend generation, confined to the shared frontend/backend event loop. */
final class TabCompletionBridge implements AutoCloseable {
    private final Channel frontend;
    private final Function<String, List<String>> roots;
    private final Function<String, Optional<CompletionStage<List<String>>>> complete;
    private final Runnable closeSession;
    private final ArrayDeque<Request> queued = new ArrayDeque<>();
    private Request active;
    private boolean backendReplyOutstanding;
    private boolean closed;
    private static final class Request {
        final ChannelHandlerContext context;
        final ByteBuf frame;
        final String text;
        boolean owned = true;
        Request(ChannelHandlerContext context, ByteBuf frame, String text) {
            this.context = context; this.frame = frame; this.text = text;
        }
        void release() { if (owned) { owned = false; frame.release(); } }
        void forward() { owned = false; context.fireChannelRead(frame); }
    }
    private ScheduledFuture<?> timeout;
    private CompletableFuture<?> pluginResult;

    TabCompletionBridge(Channel frontend, Function<String, List<String>> roots,
                        Function<String, Optional<CompletionStage<List<String>>>> complete,
                        Runnable closeSession) {
        this.frontend = frontend;
        this.roots = roots;
        this.complete = complete;
        this.closeSession = closeSession;
    }

    ChannelInboundHandlerAdapter frontendHandler() { return new Handler(true); }
    ChannelInboundHandlerAdapter backendHandler() { return new Handler(false); }

    private final class Handler extends ChannelInboundHandlerAdapter {
        private final boolean fromClient;
        private Handler(boolean fromClient) { this.fromClient = fromClient; }
        @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
            if (!(message instanceof ByteBuf frame)) {
                ReferenceCountUtil.release(message);
                closeSession.run();
                return;
            }
            if (!MinecraftTabCompletion.matches(frame,
                    fromClient ? MinecraftTabCompletion.REQUEST : MinecraftTabCompletion.RESPONSE)) {
                ctx.fireChannelRead(frame);
                return;
            }
            try {
                if (fromClient) {
                    if (closed) { frame.release(); return; }
                    String text = MinecraftTabCompletion.request(frame);
                    if (queued.size() >= 8) {
                        frame.release();
                        closeSession.run();
                        return;
                    }
                    queued.add(new Request(ctx, frame, text));
                    pump();
                } else {
                    try { acceptBackend(frame); } finally { frame.release(); }
                }
            } catch (RuntimeException invalid) {
                // A request not admitted to the queue is still ours.
                if (fromClient && frame.refCnt() > 0 && queued.stream().noneMatch(r -> r.frame == frame)
                        && (active == null || active.frame != frame)) frame.release();
                closeSession.run();
            } finally {
                RawRelay.continueAfterDrop(ctx.channel());
            }
        }
    }

    private void pump() {
        if (closed || active != null || queued.isEmpty()) return;
        Request request = queued.removeFirst();
        active = request;
        boolean root = isRoot(request.text);
        Optional<CompletionStage<List<String>>> local = root ? Optional.empty() : complete.apply(request.text);
        if (local.isPresent()) {
            request.release();
            CompletableFuture<List<String>> future = local.get().toCompletableFuture();
            pluginResult = future;
            timeout = frontend.eventLoop().schedule(() -> {
                if (active != request) return;
                finish(List.of());
                future.cancel(false);
            }, 1, TimeUnit.SECONDS);
            future.whenComplete((values, error) -> {
                try {
                    frontend.eventLoop().execute(() -> {
                        if (!closed && active == request) finish(error == null ? values : List.of());
                    });
                } catch (java.util.concurrent.RejectedExecutionException ignored) { }
            });
        } else if (backendReplyOutstanding) {
            // An expired backend request has no wire ID. Do not issue another until its reply arrives.
            request.release();
            finish(root ? roots.apply(request.text.substring(1)) : List.of());
        } else {
            backendReplyOutstanding = true;
            timeout = frontend.eventLoop().schedule(() -> {
                if (active == request) finish(root ? roots.apply(request.text.substring(1)) : List.of());
            }, 1, TimeUnit.SECONDS);
            request.forward();
        }
    }

    private static boolean isRoot(String text) {
        return text.startsWith("/") && text.chars().noneMatch(Character::isWhitespace);
    }

    private void acceptBackend(ByteBuf frame) {
        if (closed || !backendReplyOutstanding) return;
        backendReplyOutstanding = false;
        if (active == null || pluginResult != null) return; // expired request; never deliver its reply
        if (!isRoot(active.text)) { finish(List.of(), frame); return; }
        List<String> values = MinecraftTabCompletion.response(frame);
        if (isRoot(active.text)) {
            LinkedHashSet<String> merged = new LinkedHashSet<>(roots.apply(active.text.substring(1)));
            merged.addAll(values);
            values = new ArrayList<>(merged).subList(0, Math.min(100, merged.size()));
        }
        finish(values);
    }

    private void finish(List<String> values) {
        finish(values, null);
    }

    private void finish(List<String> values, ByteBuf original) {
        if (timeout != null) timeout.cancel(false);
        timeout = null;
        pluginResult = null;
        active = null;
        if (!closed && frontend.isActive() && frontend.isWritable()) {
            try {
                frontend.writeAndFlush(original == null
                                ? MinecraftTabCompletion.response(frontend.alloc(), bounded(values)) : original.retainedDuplicate())
                        .addListener(write -> { if (!write.isSuccess()) closeSession.run(); });
            } catch (RuntimeException failure) { closeSession.run(); return; }
        }
        pump();
    }

    private static List<String> bounded(List<String> values) {
        List<String> result = new ArrayList<>();
        int bytes = 2; // packet ID and result count (at most 100)
        for (String value : values) {
            if (result.size() == 100) break;
            if (value == null || value.length() > 100) continue;
            int length = value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            int framed = length + (length < 128 ? 1 : 2);
            if (bytes + framed > 32767) break;
            bytes += framed;
            result.add(value);
        }
        return result;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (timeout != null) timeout.cancel(false);
        if (pluginResult != null) pluginResult.cancel(false);
        if (active != null) active.release();
        active = null;
        Request request;
        while ((request = queued.pollFirst()) != null) request.release();
    }
}

package dev.strataproxy.infrastructure.minecraft;

import dev.strataproxy.infrastructure.minecraft.codec.MinecraftCodecException;
import dev.strataproxy.infrastructure.minecraft.codec.MinecraftCompressedFrameRewriter;
import dev.strataproxy.infrastructure.minecraft.codec.MinecraftVarInts;
import dev.strataproxy.domain.compression.CompressionAction;
import dev.strataproxy.infrastructure.observability.ProxyMetrics;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;

import java.util.List;

final class CompressionRewriteRuntime implements AutoCloseable {
    private static final String OUTCOME_BYPASS = "bypass";
    private static final String OUTCOME_REWRITTEN = "rewritten";
    private static final String OUTCOME_UNCHANGED = "unchanged";
    private static final String OUTCOME_MIXED_POLICY = "mixed_policy";
    private static final String OUTCOME_EVENT_LOOP_GUARD = "event_loop_guard";
    private static final String OUTCOME_UNSAFE_FRAME = "unsafe_frame";
    private static final String OUTCOME_FAILED = "failed";
    private static final int MAX_REWRITE_BATCH_FRAMES = 64;

    private final boolean enabled;
    private final MinecraftCompressedFrameRewriter rewriter;
    private final long maxEventLoopDelayNanos;
    private ByteBuf pending = Unpooled.EMPTY_BUFFER;

    CompressionRewriteRuntime(boolean enabled) {
        this(enabled, 25, new MinecraftCompressedFrameRewriter());
    }

    CompressionRewriteRuntime(boolean enabled, int maxEventLoopDelayMillis) {
        this(enabled, maxEventLoopDelayMillis, new MinecraftCompressedFrameRewriter());
    }

    CompressionRewriteRuntime(boolean enabled, int maxEventLoopDelayMillis, MinecraftCompressedFrameRewriter rewriter) {
        this.enabled = enabled;
        this.rewriter = rewriter;
        this.maxEventLoopDelayNanos = Math.max(0L, maxEventLoopDelayMillis) * 1_000_000L;
    }

    RewriteAttempt rewrite(
            ByteBufAllocator allocator,
            ProxyMetrics metrics,
            String serverName,
            ProxyMetrics.CompressionDirection direction,
            ByteBuf frame,
            int sourceThreshold,
            List<CompressionAction> actions,
            int maxUncompressedBytes) {
        if (!enabled) {
            return RewriteAttempt.unchanged(frame);
        }
        if (maxEventLoopDelayNanos > 0 && metrics.currentEventLoopDelayNanos() > maxEventLoopDelayNanos) {
            metrics.compressionRewrite(serverName, direction, OUTCOME_EVENT_LOOP_GUARD);
            return RewriteAttempt.unchanged(frame);
        }
        if ((actions == null || actions.isEmpty()) && !hasCompleteFrame(frame, maxUncompressedBytes)) {
            appendPending(frame, maxUncompressedBytes);
            return RewriteAttempt.hold();
        }
        if (actions == null || actions.isEmpty()) {
            metrics.compressionRewrite(serverName, direction, OUTCOME_BYPASS);
            return RewriteAttempt.unchanged(frame);
        }
        var targetThreshold = commonTargetThreshold(actions);
        if (targetThreshold < 0) {
            metrics.compressionRewrite(serverName, direction, OUTCOME_BYPASS);
            return RewriteAttempt.unchanged(frame);
        }
        if (targetThreshold == Integer.MIN_VALUE) {
            metrics.compressionRewrite(serverName, direction, OUTCOME_MIXED_POLICY);
            return RewriteAttempt.unchanged(frame);
        }
        try {
            var rewriteInput = rewriteInput(allocator, frame, actions.size(), maxUncompressedBytes);
            try {
                var startedAt = System.nanoTime();
                var rewritten = rewriter.rewriteBatch(
                        allocator,
                        rewriteInput.frames(),
                        sourceThreshold,
                        targetThreshold,
                        maxUncompressedBytes,
                        MAX_REWRITE_BATCH_FRAMES);
                var output = rewritten.frames();
                var outcome = rewritten.rewrittenFrameBytes() == rewritten.originalFrameBytes()
                        && rewritten.isCompressed() == rewritten.wasCompressed()
                        ? OUTCOME_UNCHANGED
                        : OUTCOME_REWRITTEN;
                metrics.compressionRewrite(serverName, direction, outcome, System.nanoTime() - startedAt);
                return new RewriteAttempt(output, output != frame, false);
            } finally {
                rewriteInput.close();
            }
        } catch (MinecraftCodecException exception) {
            metrics.compressionRewrite(serverName, direction, OUTCOME_UNSAFE_FRAME);
            return RewriteAttempt.unchanged(frame);
        } catch (RuntimeException exception) {
            metrics.compressionRewrite(serverName, direction, OUTCOME_FAILED);
            return RewriteAttempt.unchanged(frame);
        }
    }

    private RewriteInput rewriteInput(ByteBufAllocator allocator, ByteBuf input, int expectedFrames, int maxFrameBytes) {
        if (expectedFrames <= 0) {
            return new RewriteInput(input, false);
        }
        ByteBuf source;
        var ownedSource = false;
        if (pending.isReadable()) {
            if (pending.readableBytes() + input.readableBytes() > maxFrameBytes + 5) {
                closePending();
                throw new MinecraftCodecException("compressed rewrite pending frame exceeds maximum size");
            }
            source = allocator.buffer(pending.readableBytes() + input.readableBytes());
            ownedSource = true;
            source.writeBytes(pending, pending.readerIndex(), pending.readableBytes());
            source.writeBytes(input, input.readerIndex(), input.readableBytes());
            pending.clear();
        } else {
            source = input;
        }

        var view = source.slice();
        var completeBytes = 0;
        var frames = 0;
        while (view.isReadable() && frames < expectedFrames) {
            var probe = MinecraftVarInts.probe(view);
            if (!probe.complete()) {
                break;
            }
            if (probe.value() < 0 || probe.value() > maxFrameBytes) {
                releaseIfOwned(source, ownedSource);
                throw new MinecraftCodecException("compressed rewrite frame exceeds maximum size");
            }
            var totalBytes = probe.bytes() + probe.value();
            if (view.readableBytes() < totalBytes) {
                break;
            }
            view.skipBytes(totalBytes);
            completeBytes += totalBytes;
            frames++;
        }
        if (frames != expectedFrames) {
            releaseIfOwned(source, ownedSource);
            throw new MinecraftCodecException("compressed rewrite observed frame count does not match audit");
        }

        var trailingBytes = view.readableBytes();
        if (trailingBytes > 0) {
            if (trailingBytes > maxFrameBytes + 5) {
                releaseIfOwned(source, ownedSource);
                closePending();
                throw new MinecraftCodecException("compressed rewrite pending frame exceeds maximum size");
            }
            appendPending(view, maxFrameBytes);
        }

        if (completeBytes == source.readableBytes()) {
            return new RewriteInput(source, ownedSource);
        }
        var complete = source.retainedSlice(source.readerIndex(), completeBytes);
        releaseIfOwned(source, ownedSource);
        return new RewriteInput(complete, true);
    }

    private boolean hasCompleteFrame(ByteBuf input, int maxFrameBytes) {
        var view = input.slice();
        var probe = MinecraftVarInts.probe(view);
        if (!probe.complete()) {
            return false;
        }
        if (probe.value() < 0 || probe.value() > maxFrameBytes) {
            throw new MinecraftCodecException("compressed rewrite frame exceeds maximum size");
        }
        return view.readableBytes() >= probe.bytes() + probe.value();
    }

    private void appendPending(ByteBuf input, int maxFrameBytes) {
        if (!input.isReadable()) {
            return;
        }
        if (pending.readableBytes() + input.readableBytes() > maxFrameBytes + 5) {
            closePending();
            throw new MinecraftCodecException("compressed rewrite pending frame exceeds maximum size");
        }
        if (pending == Unpooled.EMPTY_BUFFER) {
            pending = Unpooled.buffer(Math.min(maxFrameBytes + 5, Math.max(64, input.readableBytes())));
        }
        pending.writeBytes(input, input.readerIndex(), input.readableBytes());
    }

    private static void releaseIfOwned(ByteBuf source, boolean owned) {
        if (owned && source.refCnt() > 0) {
            source.release();
        }
    }

    private static int commonTargetThreshold(List<CompressionAction> actions) {
        var target = -1;
        for (var action : actions) {
            var threshold = switch (action) {
                case CompressionAction.Threshold value -> value.bytes();
                case CompressionAction.Force value -> value.thresholdBytes();
                case CompressionAction.Bypass ignored -> -1;
            };
            if (threshold < 0) {
                return -1;
            }
            if (target < 0) {
                target = threshold;
            } else if (target != threshold) {
                return Integer.MIN_VALUE;
            }
        }
        return target;
    }

    @Override
    /** Provides close. */
    public void close() {
        closePending();
        rewriter.close();
    }

    private void closePending() {
        if (pending.refCnt() > 0) {
            pending.release();
        }
        pending = Unpooled.EMPTY_BUFFER;
    }

    private record RewriteInput(ByteBuf frames, boolean owned) implements AutoCloseable {
        @Override
        /** Provides close. */
        public void close() {
            if (owned && frames.refCnt() > 0) {
                frames.release();
            }
        }
    }

    record RewriteAttempt(ByteBuf frame, boolean replaced, boolean suppressed) {
        private static RewriteAttempt unchanged(ByteBuf frame) {
            return new RewriteAttempt(frame, false, false);
        }

        private static RewriteAttempt hold() {
            return new RewriteAttempt(Unpooled.EMPTY_BUFFER, false, true);
        }
    }
}

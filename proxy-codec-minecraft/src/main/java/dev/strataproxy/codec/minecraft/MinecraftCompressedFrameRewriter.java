package dev.strataproxy.codec.minecraft;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.util.ArrayList;
import java.util.Objects;

public final class MinecraftCompressedFrameRewriter implements AutoCloseable {
    private final MinecraftCompressionCodec codec;

    public MinecraftCompressedFrameRewriter() {
        this(new MinecraftCompressionCodec());
    }

    public MinecraftCompressedFrameRewriter(MinecraftCompressionCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public RewriteResult rewrite(
            ByteBufAllocator allocator,
            ByteBuf frame,
            int sourceThreshold,
            int targetThreshold,
            int maxUncompressedBytes) {
        Objects.requireNonNull(allocator, "allocator");
        Objects.requireNonNull(frame, "frame");
        if (sourceThreshold < 0 || targetThreshold < 0) {
            throw new IllegalArgumentException("thresholds must be non-negative");
        }
        if (maxUncompressedBytes <= 0) {
            throw new IllegalArgumentException("maxUncompressedBytes must be positive");
        }

        var original = inspectSingleFrame(frame);
        ByteBuf decoded = null;
        ByteBuf rewritten = null;
        try {
            decoded = codec.decodeFrame(allocator, frame.slice(), sourceThreshold, maxUncompressedBytes);
            rewritten = codec.encodeFrame(allocator, decoded, targetThreshold);
            var rewrittenStats = inspectSingleFrame(rewritten);
            return new RewriteResult(
                    rewritten,
                    sourceThreshold,
                    targetThreshold,
                    original.frameBytes(),
                    original.uncompressedBytes(),
                    original.compressedPayloadBytes(),
                    rewrittenStats.frameBytes(),
                    rewrittenStats.uncompressedBytes(),
                    rewrittenStats.compressedPayloadBytes());
        } catch (RuntimeException exception) {
            if (rewritten != null) {
                rewritten.release();
            }
            throw exception;
        } finally {
            if (decoded != null) {
                decoded.release();
            }
        }
    }

    public BatchRewriteResult rewriteBatch(
            ByteBufAllocator allocator,
            ByteBuf frames,
            int sourceThreshold,
            int targetThreshold,
            int maxUncompressedBytes,
            int maxFrames) {
        Objects.requireNonNull(allocator, "allocator");
        Objects.requireNonNull(frames, "frames");
        if (sourceThreshold < 0 || targetThreshold < 0) {
            throw new IllegalArgumentException("thresholds must be non-negative");
        }
        if (maxUncompressedBytes <= 0) {
            throw new IllegalArgumentException("maxUncompressedBytes must be positive");
        }
        if (maxFrames <= 0) {
            throw new IllegalArgumentException("maxFrames must be positive");
        }

        var view = frames.slice();
        var rewrittenFrames = new ArrayList<ByteBuf>();
        var frameCount = 0;
        var originalFrameBytes = 0;
        var originalUncompressedBytes = 0;
        var originalCompressedPayloadBytes = 0;
        var rewrittenFrameBytes = 0;
        var rewrittenUncompressedBytes = 0;
        var rewrittenCompressedPayloadBytes = 0;
        try {
            while (view.isReadable()) {
                if (++frameCount > maxFrames) {
                    throw new MinecraftCodecException("compressed frame rewrite batch exceeds maximum frame count");
                }
                var probe = MinecraftVarInts.probe(view);
                if (!probe.complete()) {
                    throw new MinecraftCodecException("compressed frame rewrite batch contains partial frame length");
                }
                var totalBytes = probe.bytes() + probe.value();
                if (probe.value() < 0 || totalBytes > view.readableBytes()) {
                    throw new MinecraftCodecException("compressed frame rewrite batch contains partial frame");
                }
                var frame = view.readRetainedSlice(totalBytes);
                try (var result = rewrite(allocator, frame, sourceThreshold, targetThreshold, maxUncompressedBytes)) {
                    var rewritten = result.frame().retain();
                    rewrittenFrames.add(rewritten);
                    originalFrameBytes += result.originalFrameBytes();
                    originalUncompressedBytes += result.originalUncompressedBytes();
                    originalCompressedPayloadBytes += result.originalCompressedPayloadBytes();
                    rewrittenFrameBytes += result.rewrittenFrameBytes();
                    rewrittenUncompressedBytes += result.rewrittenUncompressedBytes();
                    rewrittenCompressedPayloadBytes += result.rewrittenCompressedPayloadBytes();
                } finally {
                    frame.release();
                }
            }
            var output = allocator.compositeBuffer(rewrittenFrames.size()).addComponents(true, rewrittenFrames);
            rewrittenFrames = new ArrayList<>();
            return new BatchRewriteResult(
                    output,
                    sourceThreshold,
                    targetThreshold,
                    frameCount,
                    originalFrameBytes,
                    originalUncompressedBytes,
                    originalCompressedPayloadBytes,
                    rewrittenFrameBytes,
                    rewrittenUncompressedBytes,
                    rewrittenCompressedPayloadBytes);
        } catch (RuntimeException exception) {
            for (var rewritten : rewrittenFrames) {
                rewritten.release();
            }
            throw exception;
        }
    }

    @Override
    public void close() {
        codec.close();
    }

    private static FrameStats inspectSingleFrame(ByteBuf frame) {
        var view = frame.slice();
        var frameBytes = view.readableBytes();
        var packetLength = MinecraftVarInts.read(view);
        if (packetLength < 0 || packetLength != view.readableBytes()) {
            throw new MinecraftCodecException("compressed frame rewriter requires exactly one complete frame");
        }
        var packetFrame = view.readSlice(packetLength);
        var dataLength = MinecraftVarInts.read(packetFrame);
        if (dataLength == 0) {
            return new FrameStats(frameBytes, packetFrame.readableBytes(), 0);
        }
        return new FrameStats(frameBytes, dataLength, packetFrame.readableBytes());
    }

    private record FrameStats(int frameBytes, int uncompressedBytes, int compressedPayloadBytes) {
    }

    public record RewriteResult(
            ByteBuf frame,
            int sourceThreshold,
            int targetThreshold,
            int originalFrameBytes,
            int originalUncompressedBytes,
            int originalCompressedPayloadBytes,
            int rewrittenFrameBytes,
            int rewrittenUncompressedBytes,
            int rewrittenCompressedPayloadBytes) implements AutoCloseable {
        public RewriteResult {
            Objects.requireNonNull(frame, "frame");
        }

        public boolean wasCompressed() {
            return originalCompressedPayloadBytes > 0;
        }

        public boolean isCompressed() {
            return rewrittenCompressedPayloadBytes > 0;
        }

        public int savedBytes() {
            return originalFrameBytes - rewrittenFrameBytes;
        }

        @Override
        public void close() {
            frame.release();
        }
    }

    public record BatchRewriteResult(
            ByteBuf frames,
            int sourceThreshold,
            int targetThreshold,
            int frameCount,
            int originalFrameBytes,
            int originalUncompressedBytes,
            int originalCompressedPayloadBytes,
            int rewrittenFrameBytes,
            int rewrittenUncompressedBytes,
            int rewrittenCompressedPayloadBytes) implements AutoCloseable {
        public BatchRewriteResult {
            Objects.requireNonNull(frames, "frames");
        }

        public boolean wasCompressed() {
            return originalCompressedPayloadBytes > 0;
        }

        public boolean isCompressed() {
            return rewrittenCompressedPayloadBytes > 0;
        }

        public int savedBytes() {
            return originalFrameBytes - rewrittenFrameBytes;
        }

        @Override
        public void close() {
            frames.release();
        }
    }
}

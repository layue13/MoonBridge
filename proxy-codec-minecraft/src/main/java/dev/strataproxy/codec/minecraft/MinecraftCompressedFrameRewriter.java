package dev.strataproxy.codec.minecraft;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Rewrites Minecraft compressed frames from one compression threshold to another.
 *
 * <p>Returned buffers are owned by the result objects and released when the result is closed.</p>
 */
public final class MinecraftCompressedFrameRewriter implements AutoCloseable {
    private final MinecraftCompressionCodec codec;

    /**
     * Creates a rewriter with the default zlib codec.
     */
    public MinecraftCompressedFrameRewriter() {
        this(new MinecraftCompressionCodec());
    }

    /**
     * @param codec codec used for decode and encode operations
     */
    public MinecraftCompressedFrameRewriter(MinecraftCompressionCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    /**
     * Rewrites one complete compressed-frame envelope.
     *
     * @param allocator allocator for the rewritten buffer
     * @param frame complete source frame
     * @param sourceThreshold threshold used to decode the source frame
     * @param targetThreshold threshold used to encode the rewritten frame
     * @param maxUncompressedBytes safety limit for decoded payload size
     * @return rewrite result owning the rewritten frame
     */
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

    /**
     * Rewrites a buffer containing consecutive compressed-frame envelopes.
     *
     * @param allocator allocator for the rewritten composite buffer
     * @param frames source buffer containing complete frames
     * @param sourceThreshold threshold used to decode source frames
     * @param targetThreshold threshold used to encode rewritten frames
     * @param maxUncompressedBytes safety limit for each decoded payload
     * @param maxFrames maximum number of frames accepted in the batch
     * @return batch rewrite result owning the rewritten frame buffer
     */
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

    /**
     * Releases the underlying codec.
     */
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

    /**
     * Result for a single rewritten frame.
     *
     * @param frame rewritten frame buffer owned by this result
     * @param sourceThreshold source threshold
     * @param targetThreshold target threshold
     * @param originalFrameBytes original envelope size
     * @param originalUncompressedBytes original uncompressed payload size
     * @param originalCompressedPayloadBytes original compressed payload size, or zero when uncompressed
     * @param rewrittenFrameBytes rewritten envelope size
     * @param rewrittenUncompressedBytes rewritten uncompressed payload size
     * @param rewrittenCompressedPayloadBytes rewritten compressed payload size, or zero when uncompressed
     */
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

        /**
         * @return {@code true} when the original frame contained compressed payload bytes
         */
        public boolean wasCompressed() {
            return originalCompressedPayloadBytes > 0;
        }

        /**
         * @return {@code true} when the rewritten frame contains compressed payload bytes
         */
        public boolean isCompressed() {
            return rewrittenCompressedPayloadBytes > 0;
        }

        /**
         * @return positive value when rewriting reduced envelope size
         */
        public int savedBytes() {
            return originalFrameBytes - rewrittenFrameBytes;
        }

        @Override
        public void close() {
            frame.release();
        }
    }

    /**
     * Result for a batch of rewritten frames.
     *
     * @param frames rewritten frame buffer owned by this result
     * @param sourceThreshold source threshold
     * @param targetThreshold target threshold
     * @param frameCount number of frames rewritten
     * @param originalFrameBytes total original envelope size
     * @param originalUncompressedBytes total original uncompressed payload size
     * @param originalCompressedPayloadBytes total original compressed payload size
     * @param rewrittenFrameBytes total rewritten envelope size
     * @param rewrittenUncompressedBytes total rewritten uncompressed payload size
     * @param rewrittenCompressedPayloadBytes total rewritten compressed payload size
     */
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

        /**
         * @return {@code true} when any original frame contained compressed payload bytes
         */
        public boolean wasCompressed() {
            return originalCompressedPayloadBytes > 0;
        }

        /**
         * @return {@code true} when any rewritten frame contains compressed payload bytes
         */
        public boolean isCompressed() {
            return rewrittenCompressedPayloadBytes > 0;
        }

        /**
         * @return positive value when rewriting reduced total envelope size
         */
        public int savedBytes() {
            return originalFrameBytes - rewrittenFrameBytes;
        }

        @Override
        public void close() {
            frames.release();
        }
    }
}

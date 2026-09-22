package dev.strataproxy.infrastructure.minecraft;

import dev.strataproxy.infrastructure.minecraft.codec.MinecraftCodecException;
import dev.strataproxy.infrastructure.minecraft.codec.MinecraftVarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.List;

final class MinecraftCompressedFrameAuditSampler {
    private final int threshold;
    private final int maxFrameBytes;
    private ByteBuf pending = Unpooled.buffer();
    private boolean closed;

    MinecraftCompressedFrameAuditSampler(int threshold, int maxFrameBytes) {
        if (threshold < 0) {
            throw new IllegalArgumentException("threshold must be non-negative");
        }
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive");
        }
        this.threshold = threshold;
        this.maxFrameBytes = maxFrameBytes;
    }

    List<CompressionFrameSample> observe(ByteBuf input) {
        if (closed || !input.isReadable()) {
            return List.of();
        }
        var samples = new ArrayList<CompressionFrameSample>();
        if (pending.isReadable()) {
            if (pending.readableBytes() + input.readableBytes() > maxFrameBytes + 5) {
                close();
                throw new MinecraftCodecException("compressed frame audit exceeded maximum frame size");
            }
            pending.writeBytes(input, input.readerIndex(), input.readableBytes());
            drainCompleteFrames(pending, samples);
            pending.discardReadBytes();
            return samples;
        }

        var view = input.slice();
        drainCompleteFrames(view, samples);
        if (view.isReadable()) {
            if (view.readableBytes() > maxFrameBytes + 5) {
                close();
                throw new MinecraftCodecException("compressed frame audit exceeded maximum frame size");
            }
            pending.writeBytes(view, view.readerIndex(), view.readableBytes());
        }
        return samples;
    }

    private void drainCompleteFrames(ByteBuf input, ArrayList<CompressionFrameSample> samples) {
        while (input.isReadable()) {
            var frameLength = MinecraftVarInts.probe(input);
            if (!frameLength.complete()) {
                break;
            }
            if (frameLength.value() < 0 || frameLength.value() > maxFrameBytes) {
                close();
                throw new MinecraftCodecException("compressed audit frame exceeds maximum size");
            }
            var totalBytes = frameLength.bytes() + frameLength.value();
            if (input.readableBytes() < totalBytes) {
                break;
            }
            var frame = input.readSlice(totalBytes);
            var body = frame.slice(frameLength.bytes(), frameLength.value());
            var dataLength = MinecraftVarInts.read(body);
            if (dataLength == 0) {
                samples.add(new CompressionFrameSample(body.readableBytes(), totalBytes));
                continue;
            }
            if (dataLength < threshold) {
                throw new MinecraftCodecException("compressed audit frame below negotiated threshold");
            }
            if (dataLength > maxFrameBytes) {
                throw new MinecraftCodecException("compressed audit frame exceeds maximum uncompressed size");
            }
            samples.add(new CompressionFrameSample(dataLength, totalBytes));
        }
    }

    void close() {
        closed = true;
        if (pending.refCnt() > 0) {
            pending.release();
        }
        pending = Unpooled.EMPTY_BUFFER;
    }

    record CompressionFrameSample(long rawBytes, long compressedBytes) {
    }
}

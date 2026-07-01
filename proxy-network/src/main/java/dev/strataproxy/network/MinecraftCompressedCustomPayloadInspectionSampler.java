package dev.strataproxy.network;

import dev.strataproxy.analysis.CustomPayloadAnomalyPolicy;
import dev.strataproxy.analysis.PacketAnomaly;
import dev.strataproxy.codec.minecraft.MinecraftCodecException;
import dev.strataproxy.codec.minecraft.MinecraftCompressionCodec;
import dev.strataproxy.codec.minecraft.MinecraftCustomPayloadClassifier;
import dev.strataproxy.codec.minecraft.MinecraftVarInts;
import dev.strataproxy.protocol.PacketDirection;
import dev.strataproxy.protocol.PacketView;
import dev.strataproxy.protocol.ProtocolState;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;

import java.util.ArrayList;
import java.util.List;

final class MinecraftCompressedCustomPayloadInspectionSampler implements AutoCloseable {
    private static final int DEFAULT_MAX_INSPECTED_FRAMES = 512;

    private final int threshold;
    private final int maxFrameBytes;
    private final int largePayloadBytes;
    private final int maxInspectedFrames;
    private final CustomPayloadAnomalyPolicy policy;
    private final MinecraftCompressionCodec codec = new MinecraftCompressionCodec();
    private ByteBuf pending = Unpooled.buffer();
    private long floodWindowStartedNanos;
    private int floodWindowCount;
    private int inspectedFrames;
    private boolean closed;

    MinecraftCompressedCustomPayloadInspectionSampler(
            int threshold,
            int maxFrameBytes,
            int largePayloadBytes,
            CustomPayloadAnomalyPolicy policy) {
        this(threshold, maxFrameBytes, largePayloadBytes, policy, DEFAULT_MAX_INSPECTED_FRAMES);
    }

    MinecraftCompressedCustomPayloadInspectionSampler(
            int threshold,
            int maxFrameBytes,
            int largePayloadBytes,
            CustomPayloadAnomalyPolicy policy,
            int maxInspectedFrames) {
        if (threshold < 0) {
            throw new IllegalArgumentException("threshold must be non-negative");
        }
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive");
        }
        if (largePayloadBytes < 0) {
            throw new IllegalArgumentException("largePayloadBytes must be non-negative");
        }
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        if (maxInspectedFrames <= 0) {
            throw new IllegalArgumentException("maxInspectedFrames must be positive");
        }
        this.threshold = threshold;
        this.maxFrameBytes = maxFrameBytes;
        this.largePayloadBytes = largePayloadBytes;
        this.policy = policy;
        this.maxInspectedFrames = maxInspectedFrames;
    }

    List<InspectionResult> observe(ByteBuf input) {
        if (closed || !input.isReadable()) {
            return List.of();
        }
        if (pending.readableBytes() + input.readableBytes() > maxFrameBytes + 5) {
            close();
            throw new MinecraftCodecException("compressed custom payload inspection exceeded maximum frame size");
        }
        pending.writeBytes(input, input.readerIndex(), input.readableBytes());
        var results = new ArrayList<InspectionResult>();
        while (pending.isReadable() && !closed) {
            var frameLength = MinecraftVarInts.probe(pending);
            if (!frameLength.complete()) {
                break;
            }
            if (frameLength.value() < 0 || frameLength.value() > maxFrameBytes) {
                close();
                throw new MinecraftCodecException("compressed custom payload inspection frame exceeds maximum size");
            }
            var totalBytes = frameLength.bytes() + frameLength.value();
            if (pending.readableBytes() < totalBytes) {
                break;
            }
            var frame = pending.readRetainedSlice(totalBytes);
            try {
                inspectedFrames++;
                inspectFrame(frame, totalBytes, results);
                if (inspectedFrames >= maxInspectedFrames) {
                    close();
                }
            } finally {
                frame.release();
            }
            if (!closed) {
                pending.discardReadBytes();
            }
        }
        return results;
    }

    private void inspectFrame(ByteBuf frame, int compressedBytes, List<InspectionResult> results) {
        ByteBuf encoded = null;
        ByteBuf decoded = null;
        try {
            encoded = frame.retainedDuplicate();
            decoded = codec.decodeFrame(UnpooledByteBufAllocator.DEFAULT, encoded, threshold, maxFrameBytes);
            var packetId = packetId(decoded);
            if (packetId != MinecraftCustomPayloadInspectionSampler.SERVERBOUND_CONFIGURATION_CUSTOM_PAYLOAD_PACKET_ID) {
                return;
            }
            var classification = MinecraftCustomPayloadClassifier.classify(decoded, largePayloadBytes);
            var packet = new PacketView(
                    PacketDirection.SERVERBOUND,
                    ProtocolState.CONFIGURATION,
                    -1,
                    packetId,
                    decoded.readableBytes(),
                    compressedBytes,
                    true);
            var anomalies = new ArrayList<PacketAnomaly>(policy.evaluate(packet, classification));
            anomalies.addAll(policy.evaluateFlood(
                    packet,
                    classification,
                    recordCustomPayloadInWindow(System.nanoTime()),
                    policy.customPayloadFloodWindow()));
            results.add(new InspectionResult(classification, anomalies, compressedBytes));
        } finally {
            if (encoded != null) {
                encoded.release();
            }
            if (decoded != null) {
                decoded.release();
            }
        }
    }

    private static int packetId(ByteBuf decoded) {
        var view = decoded.retainedDuplicate();
        try {
            return MinecraftVarInts.read(view);
        } finally {
            view.release();
        }
    }

    private int recordCustomPayloadInWindow(long nowNanos) {
        var windowNanos = policy.customPayloadFloodWindow().toNanos();
        if (policy.customPayloadFloodMaxCount() <= 0) {
            return 0;
        }
        if (floodWindowStartedNanos == 0 || nowNanos - floodWindowStartedNanos >= windowNanos) {
            floodWindowStartedNanos = nowNanos;
            floodWindowCount = 0;
        }
        floodWindowCount++;
        return floodWindowCount;
    }

    @Override
    /** Provides close. */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (pending.refCnt() > 0) {
            pending.release();
        }
        pending = Unpooled.EMPTY_BUFFER;
        codec.close();
    }

    record InspectionResult(
            MinecraftCustomPayloadClassifier.CustomPayloadClassification classification,
            List<PacketAnomaly> anomalies,
            int compressedBytes) {
    }
}

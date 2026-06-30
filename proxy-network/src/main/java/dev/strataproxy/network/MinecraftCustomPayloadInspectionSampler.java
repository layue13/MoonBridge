package dev.strataproxy.network;

import dev.strataproxy.analysis.CustomPayloadAnomalyPolicy;
import dev.strataproxy.analysis.PacketAnomaly;
import dev.strataproxy.codec.minecraft.MinecraftCodecException;
import dev.strataproxy.codec.minecraft.MinecraftCustomPayloadClassifier;
import dev.strataproxy.codec.minecraft.MinecraftVarInts;
import dev.strataproxy.protocol.PacketDirection;
import dev.strataproxy.protocol.PacketView;
import dev.strataproxy.protocol.ProtocolState;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.List;

final class MinecraftCustomPayloadInspectionSampler {
    static final int SERVERBOUND_CONFIGURATION_CUSTOM_PAYLOAD_PACKET_ID = 0x01;

    private final int maxFrameBytes;
    private final int largePayloadBytes;
    private final CustomPayloadAnomalyPolicy policy;
    private ByteBuf pending = Unpooled.buffer();
    private long floodWindowStartedNanos;
    private int floodWindowCount;
    private boolean closed;

    MinecraftCustomPayloadInspectionSampler(int maxFrameBytes, int largePayloadBytes, CustomPayloadAnomalyPolicy policy) {
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive");
        }
        if (largePayloadBytes < 0) {
            throw new IllegalArgumentException("largePayloadBytes must be non-negative");
        }
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        this.maxFrameBytes = maxFrameBytes;
        this.largePayloadBytes = largePayloadBytes;
        this.policy = policy;
    }

    static MinecraftCustomPayloadInspectionSampler defaults(int maxFrameBytes) {
        return new MinecraftCustomPayloadInspectionSampler(
                maxFrameBytes,
                1 * 1024 * 1024,
                CustomPayloadAnomalyPolicy.defaults());
    }

    List<InspectionResult> observe(ByteBuf input) {
        if (closed || !input.isReadable()) {
            return List.of();
        }
        if (pending.readableBytes() + input.readableBytes() > maxFrameBytes + 5) {
            close();
            throw new MinecraftCodecException("custom payload inspection exceeded maximum frame size");
        }
        pending.writeBytes(input, input.readerIndex(), input.readableBytes());
        var results = new ArrayList<InspectionResult>();
        while (pending.isReadable()) {
            var frameLength = MinecraftVarInts.probe(pending);
            if (!frameLength.complete()) {
                break;
            }
            if (frameLength.value() < 0 || frameLength.value() > maxFrameBytes) {
                close();
                throw new MinecraftCodecException("custom payload inspection frame exceeds maximum size");
            }
            var totalBytes = frameLength.bytes() + frameLength.value();
            if (pending.readableBytes() < totalBytes) {
                break;
            }
            var frame = pending.readRetainedSlice(totalBytes);
            try {
                var body = frame.slice(frameLength.bytes(), frameLength.value());
                var bodyProbe = body.retainedDuplicate();
                int packetId;
                try {
                    packetId = MinecraftVarInts.read(bodyProbe);
                } finally {
                    bodyProbe.release();
                }
                if (packetId == SERVERBOUND_CONFIGURATION_CUSTOM_PAYLOAD_PACKET_ID) {
                    var classification = MinecraftCustomPayloadClassifier.classify(body, largePayloadBytes);
                    var packet = new PacketView(
                            PacketDirection.SERVERBOUND,
                            ProtocolState.CONFIGURATION,
                            -1,
                            packetId,
                            body.readableBytes(),
                            0,
                            false);
                    var anomalies = new ArrayList<PacketAnomaly>(policy.evaluate(packet, classification));
                    anomalies.addAll(policy.evaluateFlood(
                            packet,
                            classification,
                            recordCustomPayloadInWindow(System.nanoTime()),
                            policy.customPayloadFloodWindow()));
                    results.add(new InspectionResult(classification, anomalies));
                }
            } finally {
                frame.release();
            }
            pending.discardReadBytes();
        }
        return results;
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

    void close() {
        closed = true;
        if (pending.refCnt() > 0) {
            pending.release();
        }
        pending = Unpooled.EMPTY_BUFFER;
    }

    record InspectionResult(
            MinecraftCustomPayloadClassifier.CustomPayloadClassification classification,
            List<PacketAnomaly> anomalies) {
    }
}

package dev.strataproxy.analysis;

import dev.strataproxy.codec.minecraft.MinecraftCustomPayloadClassifier.CustomPayloadClassification;
import dev.strataproxy.codec.minecraft.MinecraftCustomPayloadClassifier.CustomPayloadKind;
import dev.strataproxy.protocol.PacketView;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class CustomPayloadAnomalyPolicy {
    private final int largePayloadWarnBytes;
    private final int unknownChannelThrottleBytes;
    private final int moddedHandshakeWarnBytes;
    private final int customPayloadFloodMaxCount;
    private final Duration customPayloadFloodWindow;

    public CustomPayloadAnomalyPolicy(
            int largePayloadWarnBytes,
            int unknownChannelThrottleBytes,
            int moddedHandshakeWarnBytes) {
        this(
                largePayloadWarnBytes,
                unknownChannelThrottleBytes,
                moddedHandshakeWarnBytes,
                200,
                Duration.ofSeconds(10));
    }

    public CustomPayloadAnomalyPolicy(
            int largePayloadWarnBytes,
            int unknownChannelThrottleBytes,
            int moddedHandshakeWarnBytes,
            int customPayloadFloodMaxCount,
            Duration customPayloadFloodWindow) {
        if (largePayloadWarnBytes < 0
                || unknownChannelThrottleBytes < 0
                || moddedHandshakeWarnBytes < 0
                || customPayloadFloodMaxCount < 0) {
            throw new IllegalArgumentException("custom payload thresholds must be non-negative");
        }
        if (customPayloadFloodMaxCount > 0 && (customPayloadFloodWindow == null || customPayloadFloodWindow.isZero() || customPayloadFloodWindow.isNegative())) {
            throw new IllegalArgumentException("custom payload flood window must be positive when flood max count is enabled");
        }
        this.largePayloadWarnBytes = largePayloadWarnBytes;
        this.unknownChannelThrottleBytes = unknownChannelThrottleBytes;
        this.moddedHandshakeWarnBytes = moddedHandshakeWarnBytes;
        this.customPayloadFloodMaxCount = customPayloadFloodMaxCount;
        this.customPayloadFloodWindow = customPayloadFloodWindow == null ? Duration.ofSeconds(10) : customPayloadFloodWindow;
    }

    public static CustomPayloadAnomalyPolicy defaults() {
        return new CustomPayloadAnomalyPolicy(1 * 1024 * 1024, 256 * 1024, 2 * 1024 * 1024, 200, Duration.ofSeconds(10));
    }

    public int largePayloadWarnBytes() {
        return largePayloadWarnBytes;
    }

    public int unknownChannelThrottleBytes() {
        return unknownChannelThrottleBytes;
    }

    public int moddedHandshakeWarnBytes() {
        return moddedHandshakeWarnBytes;
    }

    public int customPayloadFloodMaxCount() {
        return customPayloadFloodMaxCount;
    }

    public Duration customPayloadFloodWindow() {
        return customPayloadFloodWindow;
    }

    public List<PacketAnomaly> evaluate(PacketView packet, CustomPayloadClassification payload) {
        var anomalies = new ArrayList<PacketAnomaly>(2);
        if (payload.payloadBytes() >= largePayloadWarnBytes && largePayloadWarnBytes > 0) {
            anomalies.add(anomaly(
                    "custom-payload-large",
                    packet,
                    AnomalyAction.WARN,
                    payload,
                    "custom payload channel " + payload.channel() + " has " + payload.payloadBytes() + " bytes"));
        }
        if (payload.kind() == CustomPayloadKind.UNKNOWN
                && payload.payloadBytes() >= unknownChannelThrottleBytes
                && unknownChannelThrottleBytes > 0) {
            anomalies.add(anomaly(
                    "custom-payload-unknown-large",
                    packet,
                    AnomalyAction.THROTTLE,
                    payload,
                    "unknown custom payload channel " + payload.channel() + " exceeded " + unknownChannelThrottleBytes + " bytes"));
        }
        if ((payload.kind() == CustomPayloadKind.FORGE_HANDSHAKE || payload.kind() == CustomPayloadKind.FABRIC_HANDSHAKE)
                && payload.payloadBytes() >= moddedHandshakeWarnBytes
                && moddedHandshakeWarnBytes > 0) {
            anomalies.add(anomaly(
                    "modded-handshake-large",
                    packet,
                    AnomalyAction.WARN,
                    payload,
                    payload.kind().name().toLowerCase(java.util.Locale.ROOT) + " payload exceeded "
                            + moddedHandshakeWarnBytes + " bytes"));
        }
        return List.copyOf(anomalies);
    }

    public List<PacketAnomaly> evaluateFlood(
            PacketView packet,
            CustomPayloadClassification payload,
            int observedCount,
            Duration observedWindow) {
        if (customPayloadFloodMaxCount <= 0 || observedCount <= customPayloadFloodMaxCount) {
            return List.of();
        }
        return List.of(anomaly(
                "custom-payload-flood",
                packet,
                AnomalyAction.THROTTLE,
                payload,
                "custom payload count " + observedCount + " exceeded " + customPayloadFloodMaxCount
                        + " in " + observedWindow.toMillis() + "ms"));
    }

    private static PacketAnomaly anomaly(
            String rule,
            PacketView packet,
            AnomalyAction action,
            CustomPayloadClassification payload,
            String explanation) {
        return new PacketAnomaly(
                rule,
                packet,
                action,
                explanation + " (kind=" + payload.kind() + ", packetId=" + payload.packetId() + ")",
                Instant.now());
    }
}

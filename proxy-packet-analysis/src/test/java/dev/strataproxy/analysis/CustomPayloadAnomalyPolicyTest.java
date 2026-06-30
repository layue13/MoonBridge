package dev.strataproxy.analysis;

import dev.strataproxy.codec.minecraft.MinecraftCustomPayloadClassifier.CustomPayloadClassification;
import dev.strataproxy.codec.minecraft.MinecraftCustomPayloadClassifier.CustomPayloadKind;
import dev.strataproxy.protocol.PacketDirection;
import dev.strataproxy.protocol.PacketView;
import dev.strataproxy.protocol.ProtocolState;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CustomPayloadAnomalyPolicyTest {
    @Test
    void allowsSmallKnownPayloads() {
        var policy = new CustomPayloadAnomalyPolicy(1024, 512, 2048);

        var anomalies = policy.evaluate(packet(64), payload("minecraft:brand", 64, CustomPayloadKind.BRAND));

        assertTrue(anomalies.isEmpty());
    }

    @Test
    void warnsForLargePayloads() {
        var policy = new CustomPayloadAnomalyPolicy(1024, 512, 4096);

        var anomalies = policy.evaluate(packet(2048), payload("modded:registry_sync", 2048, CustomPayloadKind.REGISTRY_OR_CONFIG_SYNC));

        assertEquals(1, anomalies.size());
        assertEquals("custom-payload-large", anomalies.getFirst().ruleId());
        assertEquals(AnomalyAction.WARN, anomalies.getFirst().action());
    }

    @Test
    void throttlesUnknownLargePayloads() {
        var policy = new CustomPayloadAnomalyPolicy(4096, 512, 4096);

        var anomalies = policy.evaluate(packet(1024), payload("unknown:blob", 1024, CustomPayloadKind.UNKNOWN));

        assertEquals(1, anomalies.size());
        assertEquals("custom-payload-unknown-large", anomalies.getFirst().ruleId());
        assertEquals(AnomalyAction.THROTTLE, anomalies.getFirst().action());
    }

    @Test
    void warnsForLargeForgeOrFabricHandshakePayloads() {
        var policy = new CustomPayloadAnomalyPolicy(4096, 2048, 1024);

        var forge = policy.evaluate(packet(2048), payload("fml:handshake", 2048, CustomPayloadKind.FORGE_HANDSHAKE));
        var fabric = policy.evaluate(packet(2048), payload("fabric:registry/sync", 2048, CustomPayloadKind.FABRIC_HANDSHAKE));

        assertEquals("modded-handshake-large", forge.getFirst().ruleId());
        assertEquals("modded-handshake-large", fabric.getFirst().ruleId());
    }

    @Test
    void canReportMultipleRulesForSamePayload() {
        var policy = new CustomPayloadAnomalyPolicy(1024, 512, 4096);

        var anomalies = policy.evaluate(packet(2048), payload("unknown:blob", 2048, CustomPayloadKind.UNKNOWN));

        assertEquals(2, anomalies.size());
        assertTrue(anomalies.stream().anyMatch(anomaly -> anomaly.ruleId().equals("custom-payload-large")));
        assertTrue(anomalies.stream().anyMatch(anomaly -> anomaly.ruleId().equals("custom-payload-unknown-large")));
    }

    @Test
    void throttlesCustomPayloadFloods() {
        var policy = new CustomPayloadAnomalyPolicy(4096, 4096, 4096, 2, Duration.ofSeconds(10));

        var allowed = policy.evaluateFlood(
                packet(64),
                payload("minecraft:brand", 64, CustomPayloadKind.BRAND),
                2,
                Duration.ofSeconds(10));
        var throttled = policy.evaluateFlood(
                packet(64),
                payload("minecraft:brand", 64, CustomPayloadKind.BRAND),
                3,
                Duration.ofSeconds(10));

        assertTrue(allowed.isEmpty());
        assertEquals(1, throttled.size());
        assertEquals("custom-payload-flood", throttled.getFirst().ruleId());
        assertEquals(AnomalyAction.THROTTLE, throttled.getFirst().action());
    }

    private static PacketView packet(int rawSize) {
        return new PacketView(PacketDirection.SERVERBOUND, ProtocolState.CONFIGURATION, 763, 0x01, rawSize, 0, false);
    }

    private static CustomPayloadClassification payload(String channel, int bytes, CustomPayloadKind kind) {
        return new CustomPayloadClassification(0x01, channel, bytes, kind, bytes >= 1024);
    }
}

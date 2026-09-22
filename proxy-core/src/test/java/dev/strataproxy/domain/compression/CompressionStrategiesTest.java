package dev.strataproxy.domain.compression;

import dev.strataproxy.domain.protocol.PacketDirection;
import dev.strataproxy.domain.protocol.PacketView;
import dev.strataproxy.domain.protocol.ProtocolState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CompressionStrategiesTest {
    @Test
    void createsDisabledStrategy() {
        var action = CompressionStrategies.from("off").choose(context(4096, 0.1d, 0.75d, 0.5d, 256, 8192));

        var bypass = assertInstanceOf(CompressionAction.Bypass.class, action);
        assertEquals("compression disabled", bypass.reason());
    }

    @Test
    void createsFixedThresholdStrategy() {
        var action = CompressionStrategies.from("fixed").choose(context(4096, 0.1d, 0.75d, 0.5d, 512, 8192));

        var threshold = assertInstanceOf(CompressionAction.Threshold.class, action);
        assertEquals(512, threshold.bytes());
    }

    @Test
    void adaptiveBypassesSmallPackets() {
        var action = CompressionStrategies.from("adaptive").choose(context(128, 0.1d, 0.75d, 0.5d, 256, 8192));

        assertInstanceOf(CompressionAction.Bypass.class, action);
    }

    @Test
    void adaptiveUsesConfiguredCpuGuard() {
        var action = CompressionStrategies.from("adaptive").choose(context(4096, 0.76d, 0.75d, 0.5d, 256, 8192));

        var bypass = assertInstanceOf(CompressionAction.Bypass.class, action);
        assertEquals("cpu guard active", bypass.reason());
    }

    @Test
    void adaptiveForcesLargePayloadsWhenHistoryShowsSavings() {
        var action = CompressionStrategies.from("adaptive").choose(context(2 * 1024 * 1024, 0.95d, 0.75d, 0.4d, 256, 8192));

        var force = assertInstanceOf(CompressionAction.Force.class, action);
        assertEquals(256, force.thresholdBytes());
    }

    @Test
    void adaptiveClampsThresholdToBounds() {
        var action = CompressionStrategies.from("adaptive").choose(context(4096, 0.2d, 0.75d, 0.9d, 2048, 4096));

        var threshold = assertInstanceOf(CompressionAction.Threshold.class, action);
        assertEquals(2048, threshold.bytes());
    }

    @Test
    void rejectsUnknownMode() {
        assertThrows(IllegalArgumentException.class, () -> CompressionStrategies.from("unknown"));
    }

    private static CompressionContext context(
            int rawSize,
            double cpuLoad,
            double cpuGuard,
            double historicalCompressionRatio,
            int minThreshold,
            int maxThreshold) {
        return new CompressionContext(
                new PacketView(PacketDirection.CLIENTBOUND, ProtocolState.PLAY, 763, 0x01, rawSize, 0, false),
                40,
                40,
                cpuLoad,
                cpuGuard,
                historicalCompressionRatio,
                minThreshold,
                maxThreshold);
    }
}

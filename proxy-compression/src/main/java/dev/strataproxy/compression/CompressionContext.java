package dev.strataproxy.compression;

import dev.strataproxy.protocol.PacketView;

public record CompressionContext(
        PacketView packet,
        long playerRttMillis,
        long backendRttMillis,
        double cpuLoad,
        double cpuGuard,
        double historicalCompressionRatio,
        int minThreshold,
        int maxThreshold) {
    public CompressionContext {
        if (cpuLoad < 0.0d || cpuLoad > 1.0d) {
            throw new IllegalArgumentException("cpuLoad must be between 0 and 1");
        }
        if (cpuGuard < 0.0d || cpuGuard > 1.0d) {
            throw new IllegalArgumentException("cpuGuard must be between 0 and 1");
        }
        if (minThreshold < 0 || maxThreshold < minThreshold) {
            throw new IllegalArgumentException("invalid threshold bounds");
        }
    }
}

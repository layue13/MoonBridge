package dev.strataproxy.compression;

import dev.strataproxy.protocol.PacketView;

/**
 * Runtime inputs used to choose compression behavior.
 *
 * @param packet packet being considered
 * @param playerRttMillis estimated client round-trip time
 * @param backendRttMillis estimated backend round-trip time
 * @param cpuLoad proxy CPU load in the range {@code 0.0..1.0}
 * @param cpuGuard CPU threshold at which compression should become conservative
 * @param historicalCompressionRatio compressed/raw ratio observed for similar traffic
 * @param minThreshold minimum allowed compression threshold
 * @param maxThreshold maximum allowed compression threshold
 */
public record CompressionContext(
        PacketView packet,
        long playerRttMillis,
        long backendRttMillis,
        double cpuLoad,
        double cpuGuard,
        double historicalCompressionRatio,
        int minThreshold,
        int maxThreshold) {
    /**
     * Validates and normalizes record components.
     */
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

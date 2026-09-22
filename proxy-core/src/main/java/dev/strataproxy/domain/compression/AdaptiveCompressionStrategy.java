package dev.strataproxy.domain.compression;

/**
 * Compression strategy that adapts thresholds from packet size, CPU load, latency, and compression history.
 */
public final class AdaptiveCompressionStrategy implements CompressionStrategy {
    /**
     * Creates AdaptiveCompressionStrategy.
     */
    public AdaptiveCompressionStrategy() {
    }

    private static final int SMALL_HIGH_FREQUENCY_CUTOFF = 384;
    private static final int LARGE_PAYLOAD_CUTOFF = 1_048_576;

    @Override
    /** Provides choose. */
    public CompressionAction choose(CompressionContext context) {
        var packet = context.packet();
        if (packet.rawSize() < SMALL_HIGH_FREQUENCY_CUTOFF) {
            return new CompressionAction.Bypass("small packets are cheaper to forward uncompressed");
        }
        if (context.cpuLoad() >= context.cpuGuard() && packet.rawSize() < LARGE_PAYLOAD_CUTOFF) {
            return new CompressionAction.Bypass("cpu guard active");
        }
        if (packet.rawSize() >= LARGE_PAYLOAD_CUTOFF && context.historicalCompressionRatio() < 0.9d) {
            return new CompressionAction.Force(context.minThreshold(), "large payload with useful compression history");
        }
        var latencyBias = context.playerRttMillis() + context.backendRttMillis() > 250 ? -256 : 0;
        var cpuBias = context.cpuLoad() > 0.70d ? 1024 : 0;
        var threshold = clamp(1024 + latencyBias + cpuBias, context.minThreshold(), context.maxThreshold());
        return new CompressionAction.Threshold(threshold);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

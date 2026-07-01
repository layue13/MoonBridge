package dev.strataproxy.compression;

/**
 * Strategy that always uses the configured minimum threshold.
 */
public final class FixedCompressionStrategy implements CompressionStrategy {
    @Override
    public CompressionAction choose(CompressionContext context) {
        return new CompressionAction.Threshold(context.minThreshold());
    }
}

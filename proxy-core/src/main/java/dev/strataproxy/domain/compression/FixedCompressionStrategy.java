package dev.strataproxy.domain.compression;

/**
 * Strategy that always uses the configured minimum threshold.
 */
public final class FixedCompressionStrategy implements CompressionStrategy {
    /**
     * Creates FixedCompressionStrategy.
     */
    public FixedCompressionStrategy() {
    }

    @Override
    /** Provides choose. */
    public CompressionAction choose(CompressionContext context) {
        return new CompressionAction.Threshold(context.minThreshold());
    }
}

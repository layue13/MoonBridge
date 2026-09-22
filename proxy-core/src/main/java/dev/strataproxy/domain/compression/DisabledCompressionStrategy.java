package dev.strataproxy.domain.compression;

/**
 * Strategy that always bypasses compression.
 */
public final class DisabledCompressionStrategy implements CompressionStrategy {
    /**
     * Creates DisabledCompressionStrategy.
     */
    public DisabledCompressionStrategy() {
    }

    @Override
    /** Provides choose. */
    public CompressionAction choose(CompressionContext context) {
        return new CompressionAction.Bypass("compression disabled");
    }
}

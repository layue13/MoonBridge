package dev.strataproxy.compression;

public final class FixedCompressionStrategy implements CompressionStrategy {
    @Override
    public CompressionAction choose(CompressionContext context) {
        return new CompressionAction.Threshold(context.minThreshold());
    }
}

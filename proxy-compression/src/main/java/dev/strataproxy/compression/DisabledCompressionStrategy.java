package dev.strataproxy.compression;

public final class DisabledCompressionStrategy implements CompressionStrategy {
    @Override
    public CompressionAction choose(CompressionContext context) {
        return new CompressionAction.Bypass("compression disabled");
    }
}

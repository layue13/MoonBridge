package dev.strataproxy.compression;

public interface CompressionStrategy {
    CompressionAction choose(CompressionContext context);
}

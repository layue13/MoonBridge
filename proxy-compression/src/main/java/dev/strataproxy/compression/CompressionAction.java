package dev.strataproxy.compression;

public sealed interface CompressionAction permits CompressionAction.Bypass, CompressionAction.Threshold, CompressionAction.Force {
    record Bypass(String reason) implements CompressionAction {
    }

    record Threshold(int bytes) implements CompressionAction {
    }

    record Force(int thresholdBytes, String reason) implements CompressionAction {
    }
}

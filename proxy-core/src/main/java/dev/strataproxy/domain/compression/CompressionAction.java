package dev.strataproxy.domain.compression;

/**
 * Decision returned by a compression strategy for a packet or frame.
 */
public sealed interface CompressionAction permits CompressionAction.Bypass, CompressionAction.Threshold, CompressionAction.Force {
    /**
     * Leaves the frame uncompressed.
     *
     * @param reason diagnostic reason for bypassing compression
     */
    record Bypass(String reason) implements CompressionAction {
    }

    /**
     * Uses normal compression negotiation at a byte threshold.
     *
     * @param bytes minimum payload size to compress
     */
    record Threshold(int bytes) implements CompressionAction {
    }

    /**
     * Forces compression at the supplied threshold even when default heuristics might skip it.
     *
     * @param thresholdBytes minimum payload size to compress
     * @param reason diagnostic reason for forcing compression
     */
    record Force(int thresholdBytes, String reason) implements CompressionAction {
    }
}

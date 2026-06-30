package dev.strataproxy.network;

public record NetworkTuning(
        int maxFrameBytes,
        int connectTimeoutMillis,
        int writeBufferLowBytes,
        int writeBufferHighBytes,
        int maxConnections,
        int maxConnectionsPerAddress,
        int initialHandshakeTimeoutMillis,
        boolean proxyProtocol) {
    public NetworkTuning(
            int maxFrameBytes,
            int connectTimeoutMillis,
            int writeBufferLowBytes,
            int writeBufferHighBytes,
            int maxConnections,
            int maxConnectionsPerAddress,
            int initialHandshakeTimeoutMillis) {
        this(
                maxFrameBytes,
                connectTimeoutMillis,
                writeBufferLowBytes,
                writeBufferHighBytes,
                maxConnections,
                maxConnectionsPerAddress,
                initialHandshakeTimeoutMillis,
                false);
    }

    public NetworkTuning {
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive");
        }
        if (connectTimeoutMillis <= 0) {
            throw new IllegalArgumentException("connectTimeoutMillis must be positive");
        }
        if (writeBufferLowBytes < 0 || writeBufferHighBytes <= 0 || writeBufferLowBytes >= writeBufferHighBytes) {
            throw new IllegalArgumentException("write buffer watermarks must satisfy 0 <= low < high");
        }
        if (maxConnections <= 0 || maxConnectionsPerAddress <= 0) {
            throw new IllegalArgumentException("connection limits must be positive");
        }
        if (initialHandshakeTimeoutMillis <= 0) {
            throw new IllegalArgumentException("initialHandshakeTimeoutMillis must be positive");
        }
    }

    public static NetworkTuning defaults() {
        return new NetworkTuning(8 * 1024 * 1024, 5_000, 4 * 1024 * 1024, 16 * 1024 * 1024, 10_000, 200, 5_000, false);
    }
}

package dev.strataproxy.infrastructure.minecraft;

/**
 * Network limits and timeout values consumed by Netty server components.
 *
 * @param maxFrameBytes maximum inbound frame size
 * @param connectTimeoutMillis backend connect timeout
 * @param writeBufferLowBytes low write-buffer watermark
 * @param writeBufferHighBytes high write-buffer watermark
 * @param maxConnections global concurrent connection limit
 * @param maxConnectionsPerAddress concurrent connection limit per client address
 * @param maxNewConnectionsPerSecond global connection admission rate limit; zero disables it
 * @param maxNewConnectionsPerAddressPerSecond per-address admission rate limit; zero disables it
 * @param initialHandshakeTimeoutMillis timeout for the initial Minecraft handshake
 * @param proxyProtocol whether frontend connections may start with HAProxy PROXY protocol v1
 */
public record NetworkTuning(
        int maxFrameBytes,
        int connectTimeoutMillis,
        int writeBufferLowBytes,
        int writeBufferHighBytes,
        int maxConnections,
        int maxConnectionsPerAddress,
        int maxNewConnectionsPerSecond,
        int maxNewConnectionsPerAddressPerSecond,
        int initialHandshakeTimeoutMillis,
        boolean proxyProtocol) {
    /**
     * Creates tuning with connection rate limits disabled and PROXY protocol disabled.
     *
     * @param maxFrameBytes maximum inbound frame size
     * @param connectTimeoutMillis backend connect timeout
     * @param writeBufferLowBytes low write-buffer watermark
     * @param writeBufferHighBytes high write-buffer watermark
     * @param maxConnections global concurrent connection limit
     * @param maxConnectionsPerAddress concurrent connection limit per client address
     * @param initialHandshakeTimeoutMillis timeout for the initial Minecraft handshake
     */
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
                0,
                0,
                initialHandshakeTimeoutMillis,
                false);
    }

    /**
     * Creates tuning with connection rate limits disabled.
     *
     * @param maxFrameBytes maximum inbound frame size
     * @param connectTimeoutMillis backend connect timeout
     * @param writeBufferLowBytes low write-buffer watermark
     * @param writeBufferHighBytes high write-buffer watermark
     * @param maxConnections global concurrent connection limit
     * @param maxConnectionsPerAddress concurrent connection limit per client address
     * @param initialHandshakeTimeoutMillis timeout for the initial Minecraft handshake
     * @param proxyProtocol whether frontend connections may start with HAProxy PROXY protocol v1
     */
    public NetworkTuning(
            int maxFrameBytes,
            int connectTimeoutMillis,
            int writeBufferLowBytes,
            int writeBufferHighBytes,
            int maxConnections,
            int maxConnectionsPerAddress,
            int initialHandshakeTimeoutMillis,
            boolean proxyProtocol) {
        this(
                maxFrameBytes,
                connectTimeoutMillis,
                writeBufferLowBytes,
                writeBufferHighBytes,
                maxConnections,
                maxConnectionsPerAddress,
                0,
                0,
                initialHandshakeTimeoutMillis,
                proxyProtocol);
    }

    /**
     * Validates the network bounds used by Netty handlers.
     */
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
        if (maxNewConnectionsPerSecond < 0 || maxNewConnectionsPerAddressPerSecond < 0) {
            throw new IllegalArgumentException("connection rate limits must be >= 0");
        }
        if (initialHandshakeTimeoutMillis <= 0) {
            throw new IllegalArgumentException("initialHandshakeTimeoutMillis must be positive");
        }
    }

    /**
     * Creates the default production-oriented network tuning.
     *
     * @return default production network tuning
     */
    public static NetworkTuning defaults() {
        return new NetworkTuning(
                8 * 1024 * 1024,
                5_000,
                4 * 1024 * 1024,
                16 * 1024 * 1024,
                10_000,
                200,
                0,
                0,
                5_000,
                false);
    }
}

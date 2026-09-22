package dev.strataproxy.network;

import java.util.Objects;

/**
 * Complete construction-time configuration for a Netty proxy listener.
 *
 * <p>The listener intentionally has one constructor: all runtime policy is
 * named here instead of being spread across positional overloads.</p>
 */
public record NettyProxyServerConfig(
        int workerThreads,
        BackendResolver backendResolver,
        ProxyMetrics metrics,
        NetworkTuning tuning,
        boolean nativeTransport,
        NettyProxyRuntime runtime) {
    /** Validates the required listener collaborators. */
    public NettyProxyServerConfig {
        backendResolver = Objects.requireNonNull(backendResolver, "backendResolver");
        metrics = Objects.requireNonNull(metrics, "metrics");
        tuning = Objects.requireNonNull(tuning, "tuning");
        runtime = Objects.requireNonNull(runtime, "runtime");
    }
}

package dev.strataproxy.api.server;

import java.time.Instant;
import java.util.Objects;

/**
 * Health signal used by routers to decide whether a backend can receive new connections.
 *
 * @param status coarse health state
 * @param backendPingMillis latest measured backend latency, or {@code -1} when unknown
 * @param recentFailureRate recent connection or probe failure ratio in the range {@code 0.0..1.0}
 * @param reason optional diagnostic text for non-healthy states
 * @param updatedAt timestamp for the sample
 */
public record ServerHealth(
        ServerHealthStatus status,
        long backendPingMillis,
        double recentFailureRate,
        String reason,
        Instant updatedAt) {
    public ServerHealth {
        status = Objects.requireNonNull(status, "status");
        reason = reason == null ? "" : reason;
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (backendPingMillis < -1) {
            throw new IllegalArgumentException("backendPingMillis must be -1 or non-negative");
        }
        if (recentFailureRate < 0.0d || recentFailureRate > 1.0d) {
            throw new IllegalArgumentException("recentFailureRate must be between 0 and 1");
        }
    }

    /**
     * Creates a healthy sample with no recent failures.
     *
     * @param pingMillis measured backend latency in milliseconds
     * @return an {@link ServerHealthStatus#UP} health sample
     */
    public static ServerHealth up(long pingMillis) {
        return new ServerHealth(ServerHealthStatus.UP, pingMillis, 0.0d, "", Instant.now());
    }

    /**
     * @return {@code true} when routers may still send new players to this server
     */
    public boolean canReceiveNewConnections() {
        return status == ServerHealthStatus.UP || status == ServerHealthStatus.DEGRADED;
    }
}

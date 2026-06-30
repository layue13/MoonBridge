package dev.strataproxy.api.server;

import java.time.Instant;
import java.util.Objects;

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

    public static ServerHealth up(long pingMillis) {
        return new ServerHealth(ServerHealthStatus.UP, pingMillis, 0.0d, "", Instant.now());
    }

    public boolean canReceiveNewConnections() {
        return status == ServerHealthStatus.UP || status == ServerHealthStatus.DEGRADED;
    }
}

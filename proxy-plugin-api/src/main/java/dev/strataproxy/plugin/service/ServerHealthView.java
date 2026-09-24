package dev.strataproxy.plugin.service;

import java.time.Instant;
import java.util.Objects;

/** Immutable health sample visible to proxy plugins. */
public record ServerHealthView(
        Status status,
        long backendPingMillis,
        double recentFailureRate,
        String reason,
        Instant updatedAt) {
    public ServerHealthView {
        status = Objects.requireNonNull(status, "status");
        reason = reason == null ? "" : reason;
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public boolean canReceiveNewConnections() {
        return status == Status.UP || status == Status.DEGRADED;
    }

    public enum Status {
        UNKNOWN,
        UP,
        DEGRADED,
        MAINTENANCE,
        DOWN
    }
}

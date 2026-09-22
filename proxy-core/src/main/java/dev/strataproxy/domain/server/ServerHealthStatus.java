package dev.strataproxy.domain.server;

/**
 * Coarse backend health states understood by routing and admin views.
 */
public enum ServerHealthStatus {
    /** Backend is healthy and can receive normal traffic. */
    UP,
    /** Backend is usable, but routers should penalize it compared with healthy servers. */
    DEGRADED,
    /** Backend is intentionally unavailable for new traffic. */
    MAINTENANCE,
    /** Backend failed health checks and must not receive new traffic. */
    DOWN
}

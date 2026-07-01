package dev.strataproxy.analysis;

/**
 * Recommended response when packet analysis detects an anomaly.
 */
public enum AnomalyAction {
    /** Record or expose the anomaly without changing traffic. */
    WARN,
    /** Apply backpressure or rate limiting. */
    THROTTLE,
    /** Drop the offending packet while keeping the connection alive when possible. */
    DROP,
    /** Close the client connection. */
    DISCONNECT
}

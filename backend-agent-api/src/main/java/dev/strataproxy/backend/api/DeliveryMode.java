package dev.strataproxy.backend.api;

/** Delivery policy for a broker message. */
public enum DeliveryMode {
    /** Accepted messages are sent once and may be lost when a recipient disconnects. */
    BEST_EFFORT,
    /** In-memory delivery is retried until acknowledged while the proxy remains running. */
    RELIABLE
}

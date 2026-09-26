package dev.strataproxy.api;

/** The outcome of sending a message to an exact player connection. */
public enum MessageResult {
    /** The network write completed successfully. */
    SENT,
    /** That player connection no longer exists or is closing. */
    NOT_CONNECTED,
    /** Login, initial Forge negotiation, or a backend transition is still in progress. */
    NOT_READY,
    /** The bounded message queue is full or the client connection is not writable. */
    BACKPRESSURED
}

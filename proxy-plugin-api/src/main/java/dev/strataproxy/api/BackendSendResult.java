package dev.strataproxy.api;

/** Immediate outcome of writing a one-way message to a backend connection. */
public enum BackendSendResult {
    /** The message was written to the control connection. */
    SENT,
    /** The named backend has no currently connected instance. */
    NOT_CONNECTED,
    /** The bounded outbound queue is full or the connection is not writable. */
    BACKPRESSURED
}

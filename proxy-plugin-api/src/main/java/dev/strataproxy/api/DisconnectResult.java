package dev.strataproxy.api;

/** The outcome of closing an exact player connection. */
public enum DisconnectResult {
    /** The selected connection has been closed and its session resources released. */
    DISCONNECTED,
    /** No matching player connection exists. */
    NOT_CONNECTED
}

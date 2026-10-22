package dev.moonbridge.api;

/** Outcome of a requested proxy network transfer. */
public enum TransferStatus {
    NETWORK_READY,
    PLAYER_NOT_CONNECTED,
    SERVER_UNAVAILABLE,
    FAILED
}

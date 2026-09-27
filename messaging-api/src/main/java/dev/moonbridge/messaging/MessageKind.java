package dev.moonbridge.messaging;

/** Transport-level message semantics, independent of the application payload. */
public enum MessageKind {
    EVENT,
    REQUEST,
    REPLY
}

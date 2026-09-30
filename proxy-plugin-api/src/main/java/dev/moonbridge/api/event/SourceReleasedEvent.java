package dev.moonbridge.api.event;

import java.util.Objects;

/**
 * Callback context indicating that the proxy closed the exact source backend socket. This does
 * not assert that the backend fired a game Quit event or persisted any state; the handler confirms
 * its own durable boundary by completing successfully.
 */
public record SourceReleasedEvent(TransferContext context) {
    public SourceReleasedEvent {
        Objects.requireNonNull(context, "context");
    }
}

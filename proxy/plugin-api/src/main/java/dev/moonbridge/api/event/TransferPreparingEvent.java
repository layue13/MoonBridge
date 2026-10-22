package dev.moonbridge.api.event;

import java.util.Objects;

/**
 * Asynchronous decision phase after the proxy has connected to the target backend by TCP and before
 * it writes the backend handshake or login frames. Listeners may allow, deny, or register a durable
 * source-release callback. Preparation must not mutate state or capture/reset the player in a way
 * that requires rollback: the source remains active during this phase and transfer failure leaves
 * it active.
 */
public record TransferPreparingEvent(TransferContext context) implements Event<TransferDecision> {
    public TransferPreparingEvent {
        Objects.requireNonNull(context, "context");
    }
}

package dev.moonbridge.api.event;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** One listener's response to a transfer preparation request. Denial detail is not sent to clients. */
public sealed interface TransferDecision permits TransferDecision.Allowed,
        TransferDecision.Denied, TransferDecision.ReleaseSource {
    static TransferDecision allow() {
        return Allowed.instance();
    }

    static TransferDecision deny(String reason) {
        return new Denied(reason);
    }

    /**
     * Runs the supplied asynchronous confirmation after the proxy has closed this transfer's
     * exact source backend socket. This does not mean the backend fired a game Quit event or
     * persisted state; successful completion confirms the participant's own durable boundary.
     */
    static TransferDecision releaseSource(Supplier<CompletionStage<Void>> afterSourceClosed) {
        return new ReleaseSource(afterSourceClosed);
    }

    record Allowed() implements TransferDecision {
        private static final Allowed ALLOWED = new Allowed();

        private static Allowed instance() { return ALLOWED; }
    }

    record Denied(String reason) implements TransferDecision {
        public Denied {
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) throw new IllegalArgumentException("reason must not be blank");
        }
    }

    record ReleaseSource(Supplier<CompletionStage<Void>> afterSourceClosed) implements TransferDecision {
        public ReleaseSource {
            Objects.requireNonNull(afterSourceClosed, "afterSourceClosed");
        }
    }
}

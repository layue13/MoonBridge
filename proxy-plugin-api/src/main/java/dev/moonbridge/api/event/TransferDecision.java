package dev.moonbridge.api.event;

import java.util.Objects;

/** One listener's response to a transfer preparation request. Denial detail is not sent to clients. */
public sealed interface TransferDecision permits TransferDecision.Allowed,
        TransferDecision.Denied, TransferDecision.ReleaseSource {
    static TransferDecision allow() {
        return Allowed.instance();
    }

    static TransferDecision deny(String reason) {
        return new Denied(reason);
    }

    static TransferDecision releaseSource(SourceReleasedHandler handler) {
        return new ReleaseSource(handler);
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

    record ReleaseSource(SourceReleasedHandler handler) implements TransferDecision {
        public ReleaseSource {
            Objects.requireNonNull(handler, "handler");
        }
    }
}

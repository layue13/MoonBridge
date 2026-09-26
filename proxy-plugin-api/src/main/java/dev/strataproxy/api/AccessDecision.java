package dev.strataproxy.api;

import java.util.Objects;

/** Allows or denies a connection or login access check. */
public sealed interface AccessDecision permits AccessDecision.Allowed, AccessDecision.Denied {
    static AccessDecision allow() {
        return Allowed.INSTANCE;
    }

    static AccessDecision deny(String reason) {
        return new Denied(reason);
    }

    record Allowed() implements AccessDecision {
        private static final Allowed INSTANCE = new Allowed();
    }

    record Denied(String reason) implements AccessDecision {
        public Denied {
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank() || reason.codePointCount(0, reason.length()) > 1024) {
                throw new IllegalArgumentException("reason must contain 1 to 1024 characters");
            }
        }
    }
}

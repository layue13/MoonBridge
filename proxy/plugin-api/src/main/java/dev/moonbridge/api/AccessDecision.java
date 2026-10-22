package dev.moonbridge.api;

import java.util.Objects;
import net.kyori.adventure.text.Component;

/** Allows or denies a connection or login access check. */
public sealed interface AccessDecision permits AccessDecision.Allowed, AccessDecision.Denied {
    static AccessDecision allow() {
        return Allowed.INSTANCE;
    }

    static AccessDecision deny(String reason) {
        PlainTextValidation.validateString(reason, false, "reason");
        return new Denied(Component.text(reason));
    }

    static AccessDecision deny(Component reason) {
        return new Denied(reason);
    }

    record Allowed() implements AccessDecision {
        private static final Allowed INSTANCE = new Allowed();
    }

    record Denied(Component reason) implements AccessDecision {
        public Denied {
            Objects.requireNonNull(reason, "reason");
        }
    }
}

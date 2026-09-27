package dev.moonbridge.api;

import java.util.Objects;
import net.kyori.adventure.text.Component;

/** Decision returned by an initial placement handler. */
public sealed interface PlacementDecision permits PlacementDecision.Select, PlacementDecision.Reject {
    static PlacementDecision select(String backendName) {
        return new Select(backendName);
    }

    static PlacementDecision reject(String reason) {
        PlainTextValidation.validateString(reason, false, "reason");
        return new Reject(Component.text(reason));
    }

    static PlacementDecision reject(Component reason) {
        return new Reject(reason);
    }

    record Select(String backendName) implements PlacementDecision {
        public Select {
            Objects.requireNonNull(backendName, "backendName");
            if (backendName.isBlank()) {
                throw new IllegalArgumentException("backendName must not be blank");
            }
        }
    }

    record Reject(Component reason) implements PlacementDecision {
        public Reject {
            Objects.requireNonNull(reason, "reason");
        }
    }
}

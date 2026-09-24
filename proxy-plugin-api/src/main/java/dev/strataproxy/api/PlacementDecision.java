package dev.strataproxy.api;

import java.util.Objects;

/** Decision returned by an initial placement handler. */
public sealed interface PlacementDecision permits PlacementDecision.Select, PlacementDecision.Reject {
    static PlacementDecision select(String backendName) {
        return new Select(backendName);
    }

    static PlacementDecision reject(String reason) {
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

    record Reject(String reason) implements PlacementDecision {
        public Reject {
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
        }
    }
}

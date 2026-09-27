package dev.moonbridge.api;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;

/** Decision returned by an initial placement handler. */
public sealed interface PlacementDecision permits PlacementDecision.Select, PlacementDecision.Reject {
    /** Selects one backend. */
    static PlacementDecision select(String backendName) {
        return select(List.of(backendName));
    }

    /**
     * Selects ordered backend candidates. The proxy tries candidates in order until a backend
     * remains valid for the connection and the proxy begins forwarding the login exchange. It
     * does not switch destinations after login forwarding starts.
     */
    static PlacementDecision select(List<String> backendNames) {
        return new Select(backendNames);
    }

    static PlacementDecision reject(String reason) {
        PlainTextValidation.validateString(reason, false, "reason");
        return new Reject(Component.text(reason));
    }

    static PlacementDecision reject(Component reason) {
        return new Reject(reason);
    }

    record Select(List<String> backendNames) implements PlacementDecision {
        public Select {
            Objects.requireNonNull(backendNames, "backendNames");
            backendNames = List.copyOf(backendNames);
            if (backendNames.isEmpty() || backendNames.size() > 16) {
                throw new IllegalArgumentException("backendNames must contain between 1 and 16 names");
            }
            var uniqueNames = new HashSet<String>();
            for (String backendName : backendNames) {
                Objects.requireNonNull(backendName, "backendName");
                if (backendName.isBlank() || !backendName.equals(backendName.trim())) {
                    throw new IllegalArgumentException("backend names must not be blank or have surrounding whitespace");
                }
                if (backendName.length() > 128) {
                    throw new IllegalArgumentException("backend names must contain at most 128 characters");
                }
                if (!uniqueNames.add(backendName)) {
                    throw new IllegalArgumentException("backendNames must be unique: " + backendName);
                }
            }
        }
    }

    record Reject(Component reason) implements PlacementDecision {
        public Reject {
            Objects.requireNonNull(reason, "reason");
        }
    }
}

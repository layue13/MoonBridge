package dev.strataproxy.plugin.route;

import java.util.Objects;

/**
 * Result from a route policy: continue evaluation, select a backend, or stop
 * routing with a rejection.
 */
public record RouteDecision(Kind kind, String serverName, String reason) {
    /** The three distinct outcomes understood by the route pipeline. */
    public enum Kind {
        /** This policy has no decision; evaluate the next policy. */
        PASS,
        /** This policy selected the named backend. */
        SELECT,
        /** Routing must stop and the request must be rejected. */
        REJECT
    }

    private static final RouteDecision PASS_DECISION = new RouteDecision(Kind.PASS, "", "");

    public RouteDecision {
        Objects.requireNonNull(kind, "kind");
        serverName = serverName == null ? "" : serverName;
        reason = reason == null ? "" : reason;
        switch (kind) {
            case PASS -> {
                if (!serverName.isEmpty() || !reason.isEmpty()) {
                    throw new IllegalArgumentException("PASS must not include a server name or rejection reason");
                }
            }
            case SELECT -> {
                if (serverName.isBlank()) {
                    throw new IllegalArgumentException("SELECT requires a non-blank server name");
                }
                if (!reason.isEmpty()) {
                    throw new IllegalArgumentException("SELECT must not include a rejection reason");
                }
            }
            case REJECT -> {
                if (reason.isBlank()) {
                    throw new IllegalArgumentException("REJECT requires a non-blank reason");
                }
                if (!serverName.isEmpty()) {
                    throw new IllegalArgumentException("REJECT must not include a server name");
                }
            }
        }
    }

    /** Returns the shared no-decision result. */
    public static RouteDecision pass() {
        return PASS_DECISION;
    }

    /** Selects a backend by its registered server name. */
    public static RouteDecision select(String serverName) {
        return new RouteDecision(Kind.SELECT, serverName, "");
    }

    /** Stops routing with a human-readable rejection reason. */
    public static RouteDecision reject(String reason) {
        return new RouteDecision(Kind.REJECT, "", reason);
    }
}

package dev.strataproxy.domain.routing;

import dev.strataproxy.domain.server.RegisteredServer;

/**
 * Result of routing a connection request to a backend server.
 */
public sealed interface RoutingDecision permits RoutingDecision.Selected, RoutingDecision.Rejected {
    /**
     * Successful routing decision.
     *
     * @param server selected backend server
     * @param score effective routing score used for diagnostics
     */
    record Selected(RegisteredServer server, double score) implements RoutingDecision {
    }

    /**
     * Failed routing decision.
     *
     * @param reason stable diagnostic reason for rejection
     */
    record Rejected(String reason) implements RoutingDecision {
    }
}

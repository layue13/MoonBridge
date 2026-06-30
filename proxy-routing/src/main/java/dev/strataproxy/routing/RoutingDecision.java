package dev.strataproxy.routing;

import dev.strataproxy.api.server.RegisteredServer;

public sealed interface RoutingDecision permits RoutingDecision.Selected, RoutingDecision.Rejected {
    record Selected(RegisteredServer server, double score) implements RoutingDecision {
    }

    record Rejected(String reason) implements RoutingDecision {
    }
}

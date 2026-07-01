package dev.strataproxy.routing;

/**
 * Selects an eligible backend for an incoming connection.
 */
public interface ServerRouter {
    /**
     * Routes a request to a backend or returns a rejection reason.
     *
     * @param request connection and route constraints
     * @return routing decision
     */
    RoutingDecision route(RoutingRequest request);
}

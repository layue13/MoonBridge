package dev.strataproxy.routing;

public interface ServerRouter {
    RoutingDecision route(RoutingRequest request);
}

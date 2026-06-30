package dev.strataproxy.routing;

import dev.strataproxy.api.server.ServerCapability;

import java.net.InetSocketAddress;
import java.util.Set;

public record RoutingRequest(
        String route,
        Set<String> requiredTags,
        Set<ServerCapability> requiredCapabilities,
        int protocolVersion,
        InetSocketAddress remoteAddress) {
    public RoutingRequest {
        requiredTags = Set.copyOf(requiredTags == null ? Set.of() : requiredTags);
        requiredCapabilities = Set.copyOf(requiredCapabilities == null ? Set.of() : requiredCapabilities);
    }
}

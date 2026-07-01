package dev.strataproxy.routing;

import dev.strataproxy.api.server.ServerCapability;

import java.net.InetSocketAddress;
import java.util.Set;

/**
 * Inputs used by a router to select a backend server.
 *
 * @param route requested route, host, tag, or backend alias
 * @param requiredTags tags that a backend must contain
 * @param requiredCapabilities capabilities that a backend must expose
 * @param protocolVersion client Minecraft protocol version
 * @param remoteAddress client address, used as part of deterministic load distribution when available
 */
public record RoutingRequest(
        String route,
        Set<String> requiredTags,
        Set<ServerCapability> requiredCapabilities,
        int protocolVersion,
        InetSocketAddress remoteAddress) {
    /**
     * Validates and normalizes record components.
     */
    public RoutingRequest {
        requiredTags = Set.copyOf(requiredTags == null ? Set.of() : requiredTags);
        requiredCapabilities = Set.copyOf(requiredCapabilities == null ? Set.of() : requiredCapabilities);
    }
}

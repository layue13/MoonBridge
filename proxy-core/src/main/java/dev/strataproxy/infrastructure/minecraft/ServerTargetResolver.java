package dev.strataproxy.infrastructure.minecraft;

import dev.strataproxy.domain.server.RegisteredServer;

import java.util.Optional;

interface ServerTargetResolver {
    Optional<RegisteredServer> resolveTarget(String serverName);

    default Optional<RegisteredServer> resolveTarget(String serverName, int protocolVersion) {
        return resolveTarget(serverName);
    }

    static ServerTargetResolver unavailable() {
        return ignored -> Optional.empty();
    }

    static ServerTargetResolver from(Object resolver) {
        return resolver instanceof ServerTargetResolver targetResolver
                ? targetResolver
                : unavailable();
    }
}

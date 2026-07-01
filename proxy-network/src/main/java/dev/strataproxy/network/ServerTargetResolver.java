package dev.strataproxy.network;

import dev.strataproxy.api.server.RegisteredServer;

import java.util.Optional;

interface ServerTargetResolver {
    Optional<RegisteredServer> resolveTarget(String serverName);

    static ServerTargetResolver unavailable() {
        return ignored -> Optional.empty();
    }

    static ServerTargetResolver from(Object resolver) {
        return resolver instanceof ServerTargetResolver targetResolver
                ? targetResolver
                : unavailable();
    }
}

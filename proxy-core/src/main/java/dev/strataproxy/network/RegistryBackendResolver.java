package dev.strataproxy.network;

import dev.strataproxy.api.server.RegisteredServer;
import dev.strataproxy.api.server.ServerRegistry;

import java.net.SocketAddress;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/** Minimal, deterministic backend selection when a stage policy passes. */
public final class RegistryBackendResolver implements BackendResolver, ServerTargetResolver {
    private final ServerRegistry registry;

    public RegistryBackendResolver(ServerRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public Optional<RegisteredServer> resolve(MinecraftHandshake handshake, SocketAddress remoteAddress) {
        var host = normalizeRouteHost(handshake.requestedHost());
        var servers = registry.snapshot().stream()
                .sorted(Comparator.comparing(server -> server.descriptor().name()))
                .toList();
        var hostMatches = servers.stream().filter(server -> matchesHost(host, server)).toList();
        // A known host with unavailable backends must not fall through to another gameplay server.
        return (hostMatches.isEmpty() ? servers : hostMatches).stream()
                .filter(server -> eligible(server, handshake.protocolVersion()))
                .findFirst();
    }

    @Override
    public Optional<RegisteredServer> resolveTarget(String serverName) {
        return resolveTarget(serverName, -1);
    }

    @Override
    public Optional<RegisteredServer> resolveTarget(String serverName, int protocolVersion) {
        if (serverName == null || serverName.isBlank()) {
            return Optional.empty();
        }
        return registry.get(serverName.trim()).filter(server -> eligible(server, protocolVersion));
    }

    private static boolean eligible(RegisteredServer server, int protocolVersion) {
        return !server.draining()
                && server.health().canReceiveNewConnections()
                && !server.load().isHardFull()
                && (protocolVersion < 0 || server.descriptor().protocolRange().accepts(protocolVersion));
    }

    private static boolean matchesHost(String host, RegisteredServer server) {
        if (host.isBlank()) {
            return false;
        }
        var descriptor = server.descriptor();
        if (descriptor.name().equalsIgnoreCase(host)) {
            return true;
        }
        if (descriptor.tags().stream().anyMatch(tag -> tag.equalsIgnoreCase(host))) {
            return true;
        }
        var metadata = descriptor.metadata();
        return host.equalsIgnoreCase(metadata.get("host")) || host.equalsIgnoreCase(metadata.get("route"));
    }

    static String normalizeRouteHost(String requestedHost) {
        if (requestedHost == null || requestedHost.isBlank()) {
            return "";
        }
        var host = requestedHost.strip();
        var nul = host.indexOf('\0');
        if (nul >= 0) {
            host = host.substring(0, nul);
        }
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return host;
    }
}

package dev.strataproxy.infrastructure.minecraft;

import dev.strataproxy.domain.server.RegisteredServer;

import java.net.SocketAddress;
import java.util.Optional;

/**
 * Resolves the backend server for a newly handshaken client connection.
 */
public interface BackendResolver {
    /**
     * Selects a backend from handshake data and client address.
     *
     * @param handshake parsed Minecraft handshake
     * @param remoteAddress client remote address
     * @return selected backend, if any
     */
    Optional<RegisteredServer> resolve(MinecraftHandshake handshake, SocketAddress remoteAddress);
}

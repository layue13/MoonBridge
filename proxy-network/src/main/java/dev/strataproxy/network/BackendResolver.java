package dev.strataproxy.network;

import dev.strataproxy.api.server.RegisteredServer;

import java.net.SocketAddress;
import java.util.Optional;

public interface BackendResolver {
    Optional<RegisteredServer> resolve(MinecraftHandshake handshake, SocketAddress remoteAddress);
}

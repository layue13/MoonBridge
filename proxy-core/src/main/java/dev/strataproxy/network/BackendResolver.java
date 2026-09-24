package dev.strataproxy.network;

import dev.strataproxy.api.server.RegisteredServer;
import dev.strataproxy.plugin.service.PlayerIdentity;

import java.net.SocketAddress;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

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

    /** Selects the first backend after the player's login identity is available. */
    default CompletionStage<Optional<RegisteredServer>> resolveInitial(
            MinecraftHandshake handshake,
            SocketAddress remoteAddress,
            PlayerIdentity identity,
            String playerName) {
        return CompletableFuture.completedFuture(resolve(handshake, remoteAddress));
    }
}

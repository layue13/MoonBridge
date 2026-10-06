package dev.moonbridge.core.session.transfer;

import dev.moonbridge.api.PlayerIdentity;
import dev.moonbridge.api.PlayerView;
import dev.moonbridge.core.backend.BackendCatalog;
import dev.moonbridge.core.backend.BackendView;
import dev.moonbridge.core.event.TransferPreparation;
import dev.moonbridge.core.net.NetworkTransport;
import dev.moonbridge.core.relay.RawRelay;
import dev.moonbridge.core.session.play.KeepAliveBridge;
import dev.moonbridge.core.session.play.PlayObservation;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.resolver.AddressResolverGroup;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * What a {@link TransferCoordinator} needs from its session and the proxy. The session keeps ownership of
 * the connection state; the coordinator only reads it and commits the two transitions below.
 * All methods are called on the session's frontend event loop.
 */
public interface TransferHost {
    Channel frontend();
    Channel backend();
    RawRelay.Link relay();
    BackendView selected();
    PlayerView view();
    PlayerIdentity identity();
    /** True once the player has been admitted to a backend (post login success). */
    boolean published();
    PlayObservation observation();
    KeepAliveBridge.State keepAlives();
    boolean closed();
    boolean disconnecting();
    void closeSession();

    BackendCatalog catalog();
    TransferPreparation selectPreparation();
    Duration eventTimeout();
    Duration cutoverTimeout();
    NetworkTransport transport();
    AddressResolverGroup<InetSocketAddress> resolver();

    ByteBuf backendHandshake(String backendName, long backendEpoch);
    void installTabCompletion(Channel backend);
    /** Strips login-phase handlers from {@code channel} (after {@code beforeRemoval}) and installs lifecycle handling. */
    CompletableFuture<Void> swapHandshakeCodecs(Channel channel, Runnable beforeRemoval);

    /** The source backend is gone and no destination exists yet: the player has no current server. */
    void sourceReleased();
    /** The replacement backend now serves the player. */
    void cutover(Channel backend, BackendView selected, PlayObservation observation, RawRelay.Link relay,
                 Optional<String> previousServer);
}

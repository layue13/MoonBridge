package dev.strataproxy.api;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Queries players and performs operations bound to an exact player connection. */
public interface Players {
    /** Returns a snapshot only when this exact connection is still online. */
    Optional<PlayerView> find(PlayerIdentity identity);

    /** Returns an immutable snapshot of currently connected players. */
    List<PlayerView> online();

    /**
     * Requests a transfer by backend name. NETWORK_READY means the proxy wrote the world
     * transition after the target's protocol handshake; it does not assert plugin game readiness.
     * A failed target handshake after the client has switched chains closes the player session.
     * When accessed through PluginContext, results complete off the player's I/O loop.
     */
    CompletionStage<TransferResult> transfer(PlayerIdentity identity, String backendName);

    /**
     * Sends a plain-text message to this exact connection. Text may contain up to 1024 Unicode
     * code points, including an empty message. Login, initial Forge negotiation, and transfer
     * transitions return NOT_READY. At most 64 messages per connection may be outstanding;
     * a full queue or unwritable connection returns BACKPRESSURED without waiting for capacity.
     * SENT means the network write completed, not that the client displayed the message.
     * Network write failures complete the stage exceptionally; invalid arguments throw immediately.
     * Through PluginContext, the result completes off the player's I/O loop.
     */
    CompletionStage<MessageResult> sendMessage(PlayerIdentity identity, String message);

    /**
     * Closes this exact connection, including an identified player still in admission or placement.
     * The nonblank plain-text reason must contain at most 1024 Unicode code points. Its delivery is
     * best effort, with at most five seconds allowed for the write before closing. DISCONNECTED
     * means the connection has been cleaned up and closed, not that the client read the reason.
     * Repeated requests while closing join the same operation. Cancelling the returned stage does
     * not undo an accepted disconnect. Stale connection identities return NOT_CONNECTED.
     * Invalid arguments throw immediately. Through PluginContext, completion occurs off I/O.
     */
    CompletionStage<DisconnectResult> disconnect(PlayerIdentity identity, String reason);
}

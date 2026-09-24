package dev.strataproxy.api;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Queries connected players and requests proxy-level backend transfers. */
public interface Players {
    /** Returns a snapshot only when this exact connection is still online. */
    Optional<PlayerView> find(PlayerIdentity identity);

    /** Returns an immutable snapshot of currently connected players. */
    List<PlayerView> online();

    /** Requests a transfer by backend name; NETWORK_READY does not mean the game is ready. */
    CompletionStage<TransferResult> transfer(PlayerIdentity identity, String backendName);
}

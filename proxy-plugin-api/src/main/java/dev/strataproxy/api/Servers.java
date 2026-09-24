package dev.strataproxy.api;

import java.util.List;
import java.util.Optional;

/** Queries immutable snapshots of the backends currently known to the proxy. */
public interface Servers {
    /** Returns the latest immutable snapshot for a registered backend. */
    Optional<ServerView> find(String backendName);

    /** Returns an immutable snapshot of all backends in the shared directory. */
    List<ServerView> all();

    /**
     * Creates a plugin-owned registration in the shared backend directory. Re-registering a
     * name owned by this plugin starts a new generation and invalidates its previous handle.
     * Names owned by static configuration or another plugin cannot be replaced.
     */
    ServerRegistration register(ServerDefinition definition);
}

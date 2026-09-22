package dev.strataproxy.domain.server;

import java.util.Collection;
import java.util.Optional;

/**
 * Mutable registry of backend servers visible to routing, admin APIs, and plugins.
 */
public interface ServerRegistry {
    /**
     * Registers a new backend.
     *
     * @param descriptor backend configuration
     * @return the registered runtime view
     * @throws IllegalArgumentException when a server with the same name already exists
     */
    RegisteredServer register(ServerDescriptor descriptor);

    /**
     * Registers a backend or replaces the descriptor of an existing backend with the same name.
     *
     * @param descriptor backend configuration
     * @return the current runtime view for the backend
     */
    RegisteredServer registerOrReplace(ServerDescriptor descriptor);

    /**
     * Removes a backend or marks it for draining, depending on implementation and policy.
     *
     * @param name server name
     * @param policy drain behavior to apply before removal
     * @return {@code true} when a matching server was found
     */
    boolean unregister(String name, DrainPolicy policy);

    /**
 * Gets value.
 *
     * @param name server name
     * @return the matching registered server, if present
     */
    Optional<RegisteredServer> get(String name);

    /**
 * Provides snapshot.
 *
     * @return point-in-time snapshot of registered servers
     */
    Collection<RegisteredServer> snapshot();

    /**
     * Updates runtime health for a registered server.
     *
     * @param name server name
     * @param health latest health sample
     */
    void updateHealth(String name, ServerHealth health);

    /**
     * Updates runtime load for a registered server.
     *
     * @param name server name
     * @param load latest load sample
     */
    void updateLoad(String name, ServerLoad load);

    /**
     * Enables or disables drain mode for an existing server.
     *
     * @param name server name
     * @param drainMode whether the server should reject new routed players
     */
    void updateDrainMode(String name, boolean drainMode);
}

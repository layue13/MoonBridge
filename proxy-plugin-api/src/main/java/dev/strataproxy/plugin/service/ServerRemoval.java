package dev.strataproxy.plugin.service;

import java.time.Duration;

/**
 * Plugin request to remove a backend server.
 *
 * @param rejectNewConnections whether new routing should be rejected immediately
 * @param migrateExistingPlayers whether existing players should be moved away before removal
 * @param gracePeriod maximum time allowed for graceful migration
 * @param persistence whether the removal should be written to the registry store
 */
public record ServerRemoval(
        boolean rejectNewConnections,
        boolean migrateExistingPlayers,
        Duration gracePeriod,
        ServerPersistence persistence) {
    /**
     * Validates and normalizes record components.
     */
    public ServerRemoval {
        gracePeriod = gracePeriod == null ? Duration.ZERO : gracePeriod;
        persistence = persistence == null ? ServerPersistence.EPHEMERAL : persistence;
    }

    /**
     * Creates an immediate runtime-only removal.
     *
     * @return runtime-only removal request
     */
    public static ServerRemoval ephemeral() {
        return new ServerRemoval(true, false, Duration.ZERO, ServerPersistence.EPHEMERAL);
    }

    /**
     * Creates an immediate persisted removal.
     *
     * @return persisted removal request
     */
    public static ServerRemoval persistent() {
        return new ServerRemoval(true, false, Duration.ZERO, ServerPersistence.PERSISTENT);
    }
}

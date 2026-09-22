package dev.strataproxy.domain.server;

import java.time.Duration;

/**
 * Describes how a registered backend should be drained when it is removed or taken out of rotation.
 *
 * @param rejectNewConnections whether the proxy should immediately stop routing new players to the server
 * @param migrateExistingPlayers whether existing players should be moved away before the server disappears
 * @param gracePeriod maximum time allowed for graceful migration before removal continues
 */
public record DrainPolicy(boolean rejectNewConnections, boolean migrateExistingPlayers, Duration gracePeriod) {
    /**
     * Validates and normalizes record components.
     */
    public DrainPolicy {
        gracePeriod = gracePeriod == null ? Duration.ZERO : gracePeriod;
    }

    /**
     * Creates a policy that rejects new connections immediately and does not migrate existing players.
     *
     * @return the default immediate-drain policy
     */
    public static DrainPolicy rejectNew() {
        return new DrainPolicy(true, false, Duration.ZERO);
    }
}

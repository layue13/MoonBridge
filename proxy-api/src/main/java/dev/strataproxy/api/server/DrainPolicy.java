package dev.strataproxy.api.server;

import java.time.Duration;

public record DrainPolicy(boolean rejectNewConnections, boolean migrateExistingPlayers, Duration gracePeriod) {
    public DrainPolicy {
        gracePeriod = gracePeriod == null ? Duration.ZERO : gracePeriod;
    }

    public static DrainPolicy rejectNew() {
        return new DrainPolicy(true, false, Duration.ZERO);
    }
}

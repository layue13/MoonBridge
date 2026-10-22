package dev.moonbridge.core.plugin;

import java.time.Duration;

/** Indicates an initial placement callback did not finish within its configured deadline. */
public final class PlacementTimeoutException extends RuntimeException {
    public PlacementTimeoutException(Duration timeout) {
        super("Initial placement handler exceeded " + timeout);
    }
}

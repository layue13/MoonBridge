package dev.strataproxy.plugin.route;

/**
 * Lifecycle point at which a route policy is asked to choose a backend.
 */
public enum RouteStage {
    /**
     * Initial backend selection after Login Start (and, in online mode, after
     * Mojang authentication), before the client's first backend connection.
     * The context still includes the requested host and protocol version.
     */
    INITIAL,

    /** Selection of a destination for a player already connected to a backend. */
    TRANSFER
}

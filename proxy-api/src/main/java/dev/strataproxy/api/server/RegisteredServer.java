package dev.strataproxy.api.server;

/**
 * Runtime view of a backend server registered with the proxy.
 *
 * <p>The descriptor is the configured identity and routing metadata. Health and load are mutable runtime signals
 * supplied by probes and traffic accounting.</p>
 */
public interface RegisteredServer {
    /**
     * @return immutable configured identity and routing metadata for the server
     */
    ServerDescriptor descriptor();

    /**
     * @return latest health probe result known to the registry
     */
    ServerHealth health();

    /**
     * @return latest load sample known to the registry
     */
    ServerLoad load();

    /**
     * @return {@code true} when the server is still visible but should not receive new players
     */
    boolean draining();
}

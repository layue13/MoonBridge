package dev.strataproxy.plugin.service;

import java.util.Collection;
import java.util.Optional;

/**
 * Read-only backend lookup service exposed to plugins.
 */
public interface ServerService {
    /**
 * Provides find.
 *
     * @param serverName backend name
     * @return backend view when registered
     */
    Optional<ServerView> find(String serverName);

    /**
     * Finds the first registered backend with a tag.
     *
     * @param tag tag to match
     * @return matching backend view, if any
     */
    Optional<ServerView> firstWithTag(String tag);

    /**
 * Provides servers.
 *
     * @return snapshot of registered backend views
     */
    Collection<ServerView> servers();
}

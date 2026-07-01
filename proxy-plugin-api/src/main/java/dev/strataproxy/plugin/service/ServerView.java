package dev.strataproxy.plugin.service;

import java.net.InetSocketAddress;
import java.util.Set;

/**
 * Read-only plugin-facing view of a registered backend server.
 *
 * @param name backend name
 * @param address backend socket address
 * @param tags configured routing tags
 * @param drainMode whether the server is currently draining
 * @param softCapacity configured soft player capacity
 * @param hardCapacity configured hard player capacity
 */
public record ServerView(
        String name,
        InetSocketAddress address,
        Set<String> tags,
        boolean drainMode,
        int softCapacity,
        int hardCapacity) {
    /**
     * Validates and normalizes record components.
     */
    public ServerView {
        tags = tags == null ? Set.of() : Set.copyOf(tags);
    }
}

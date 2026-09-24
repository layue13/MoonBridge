package dev.strataproxy.plugin.service;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Map;
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
 * @param capabilities backend feature names
 * @param protocolRange accepted client protocol versions
 * @param weight reserved relative weight for a future route policy
 * @param metadata configured backend metadata
 * @param health most recently observed backend health
 * @param load most recently observed proxy-side load
 */
public record ServerView(
        String name,
        InetSocketAddress address,
        Set<String> tags,
        boolean drainMode,
        int softCapacity,
        int hardCapacity,
        Set<String> capabilities,
        ServerProtocolRange protocolRange,
        int weight,
        Map<String, String> metadata,
        ServerHealthView health,
        ServerLoadView load) {
    /**
     * Validates and normalizes record components.
     */
    public ServerView {
        tags = tags == null ? Set.of() : Set.copyOf(tags);
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        protocolRange = protocolRange == null ? ServerProtocolRange.any() : protocolRange;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        health = health == null
                ? new ServerHealthView(ServerHealthView.Status.UNKNOWN, -1, 0, "", Instant.EPOCH)
                : health;
        load = load == null
                ? new ServerLoadView(0, softCapacity, hardCapacity, 0, 0, 0, 0)
                : load;
    }

    /** Preserves the original six-field constructor for existing plugins. */
    public ServerView(String name, InetSocketAddress address, Set<String> tags,
                      boolean drainMode, int softCapacity, int hardCapacity) {
        this(name, address, tags, drainMode, softCapacity, hardCapacity,
                Set.of(), ServerProtocolRange.any(), 100, Map.of(), null, null);
    }

    /** Whether this snapshot permits another connection, before protocol-specific checks. */
    public boolean availableForNewConnections() {
        return !drainMode && health.canReceiveNewConnections() && !load.isHardFull();
    }
}

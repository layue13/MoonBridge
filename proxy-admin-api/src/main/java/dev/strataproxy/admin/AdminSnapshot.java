package dev.strataproxy.admin;

import dev.strataproxy.api.server.RegisteredServer;
import dev.strataproxy.observability.MetricEvent;

import java.time.Instant;
import java.util.List;

/**
 * Point-in-time state exposed by admin diagnostics.
 *
 * @param onlinePlayers current online player count
 * @param registeredServers number of registered backend servers
 * @param servers current registered server views
 * @param recentEvents recent metric events retained for diagnostics
 * @param createdAt snapshot creation time
 */
public record AdminSnapshot(
        int onlinePlayers,
        int registeredServers,
        List<RegisteredServer> servers,
        List<MetricEvent> recentEvents,
        Instant createdAt) {
    /**
     * Validates and normalizes record components.
     */
    public AdminSnapshot {
        servers = List.copyOf(servers == null ? List.of() : servers);
        recentEvents = List.copyOf(recentEvents == null ? List.of() : recentEvents);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}

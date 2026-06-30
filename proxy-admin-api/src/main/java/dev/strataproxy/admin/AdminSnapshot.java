package dev.strataproxy.admin;

import dev.strataproxy.api.server.RegisteredServer;
import dev.strataproxy.observability.MetricEvent;

import java.time.Instant;
import java.util.List;

public record AdminSnapshot(
        int onlinePlayers,
        int registeredServers,
        List<RegisteredServer> servers,
        List<MetricEvent> recentEvents,
        Instant createdAt) {
    public AdminSnapshot {
        servers = List.copyOf(servers == null ? List.of() : servers);
        recentEvents = List.copyOf(recentEvents == null ? List.of() : recentEvents);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}

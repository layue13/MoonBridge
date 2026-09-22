package dev.strataproxy.network;

import dev.strataproxy.plugin.service.PlayerIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ProxyMetricsTest {
    @Test
    void oldConnectionCloseDoesNotRemoveNewerSessionWithTheSameName() {
        var metrics = new ProxyMetrics();
        metrics.playerSessionStarted("Steve", null, "connection-old", "lobby", "127.0.0.1:10001");
        metrics.playerSessionStarted("Steve", null, "connection-new", "survival", "127.0.0.1:10002");

        metrics.playerSessionClosed("Steve", "connection-old");

        var current = metrics.findPlayerSession("steve").orElseThrow();
        assertEquals("connection-new", current.connectionId());
        assertEquals("survival", current.server());
        assertEquals("connection-new", metrics.snapshot().playerSessions().get("Steve").connectionId());
    }

    @Test
    void delayedTransferForOldConnectionDoesNotOverwriteNewerSession() {
        var metrics = new ProxyMetrics();
        metrics.playerSessionStarted("Steve", null, "connection-old", "lobby", "127.0.0.1:10001");
        metrics.playerSessionStarted("Steve", null, "connection-new", "survival", "127.0.0.1:10002");

        metrics.playerTransfer(true, "network_ready", "Steve", "connection-old", "lobby", "minigame", "127.0.0.1:10001");

        var current = metrics.findPlayerSession("Steve").orElseThrow();
        assertEquals("connection-new", current.connectionId());
        assertEquals("survival", current.server());
    }

    @Test
    void connectionIdentityFindsTheExactSessionAfterANameIsReused() {
        var metrics = new ProxyMetrics();
        var oldPlayerId = java.util.UUID.randomUUID();
        var newPlayerId = java.util.UUID.randomUUID();
        metrics.playerSessionStarted("Steve", oldPlayerId, "connection-old", "lobby", "127.0.0.1:10001");
        metrics.playerSessionStarted("Steve", newPlayerId, "connection-new", "survival", "127.0.0.1:10002");

        var oldSession = metrics.findPlayerSession(new PlayerIdentity(oldPlayerId, "connection-old")).orElseThrow();
        var newSession = metrics.findPlayerSession(new PlayerIdentity(newPlayerId, "connection-new")).orElseThrow();

        assertEquals("lobby", oldSession.server());
        assertEquals("survival", newSession.server());
    }
}

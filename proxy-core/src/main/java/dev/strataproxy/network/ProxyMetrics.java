package dev.strataproxy.network;

import java.util.Map;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Small in-process runtime state used by routing, status replies and plugins. */
public final class ProxyMetrics {
    private final LongAdder acceptedConnections = new LongAdder();
    private final LongAdder rejectedConnections = new LongAdder();
    private final LongAdder handshakeTimeouts = new LongAdder();
    private final LongAdder routedConnections = new LongAdder();
    private final LongAdder failedRoutes = new LongAdder();
    private final LongAdder backendConnectFailures = new LongAdder();
    private final LongAdder frontendToBackendBytes = new LongAdder();
    private final LongAdder backendToFrontendBytes = new LongAdder();
    private final AtomicLong activeConnections = new AtomicLong();
    private final AtomicLong eventLoopDelayNanos = new AtomicLong();
    private final Map<String, LongAdder> rejectedByReason = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> backendReplacements = new ConcurrentHashMap<>();
    private final Map<String, MutableServerTraffic> serverTraffic = new ConcurrentHashMap<>();
    private final Map<String, MutableServerConnections> serverConnections = new ConcurrentHashMap<>();
    private final Map<String, PlayerSession> playerSessionsByConnection = new ConcurrentHashMap<>();
    private final Map<String, String> currentConnectionByPlayer = new ConcurrentHashMap<>();

    public void acceptedConnection() { acceptedConnections.increment(); activeConnections.incrementAndGet(); }
    public void closedConnection() { activeConnections.updateAndGet(value -> Math.max(0, value - 1)); }
    public void rejectedConnection(String reason) {
        rejectedConnections.increment();
        rejectedByReason.computeIfAbsent(normalize(reason), ignored -> new LongAdder()).increment();
    }
    public void handshakeTimeout() { handshakeTimeouts.increment(); }
    public void routedConnection() { routedConnections.increment(); }
    public void failedRoute() { failedRoutes.increment(); }
    public void backendConnectFailure() { backendConnectFailures.increment(); }
    public void backendReplacement(String outcome) {
        backendReplacements.computeIfAbsent(normalize(outcome), ignored -> new LongAdder()).increment();
    }
    public void frontendToBackendBytes(long bytes) { frontendToBackendBytes.add(Math.max(0, bytes)); }
    public void backendToFrontendBytes(long bytes) { backendToFrontendBytes.add(Math.max(0, bytes)); }
    public void frontendToBackendBytes(String server, long bytes) {
        frontendToBackendBytes(bytes); serverTraffic(server).frontendToBackendBytes.add(Math.max(0, bytes));
    }
    public void backendToFrontendBytes(String server, long bytes) {
        backendToFrontendBytes(bytes); serverTraffic(server).backendToFrontendBytes.add(Math.max(0, bytes));
    }
    public ServerTrafficRecorder serverTrafficRecorder(String server) { return new ServerTrafficRecorder(this, normalize(server)); }
    public void serverConnectionOpened(String server) {
        var state = serverConnections.computeIfAbsent(normalize(server), ignored -> new MutableServerConnections());
        state.activeConnections.incrementAndGet(); state.routedConnections.increment();
    }
    public void serverConnectionClosed(String server) {
        var state = serverConnections.get(normalize(server));
        if (state != null) {
            state.activeConnections.updateAndGet(value -> Math.max(0, value - 1));
        }
    }
    public long serverConnectionCount(String server) {
        var state = serverConnections.get(normalize(server));
        return state == null ? 0 : Math.max(0, state.activeConnections.get());
    }
    public void playerSessionStarted(String player, String server, String remoteAddress) {
        playerSessionStarted(player, null, "", server, remoteAddress);
    }
    public void playerSessionStarted(String player, java.util.UUID playerId, String connectionId, String server, String remoteAddress) {
        if (player == null || player.isBlank()) {
            return;
        }
        var name = player.trim();
        var key = connectionKey(name, connectionId);
        playerSessionsByConnection.put(key, new PlayerSession(name, playerId, key, normalize(server), normalize(remoteAddress)));
        currentConnectionByPlayer.put(playerKey(name), key);
    }
    public void playerSessionClosed(String player) {
        if (player == null || player.isBlank()) {
            return;
        }
        var name = player.trim();
        var key = currentConnectionByPlayer.remove(playerKey(name));
        if (key != null) {
            playerSessionsByConnection.remove(key);
        }
    }
    public void playerSessionClosed(String player, String connectionId) {
        if (player == null || player.isBlank()) {
            return;
        }
        var name = player.trim();
        var key = connectionKey(name, connectionId);
        playerSessionsByConnection.remove(key);
        currentConnectionByPlayer.remove(playerKey(name), key);
    }
    public void playerTransfer(boolean success, String outcome, String player, String sourceServer, String targetServer, String remoteAddress) {
        if (success && player != null && !player.isBlank()) {
            var key = currentConnectionByPlayer.get(playerKey(player));
            updatePlayerServer(key, targetServer, remoteAddress);
        }
    }
    public void playerTransfer(
            boolean success,
            String outcome,
            String player,
            String connectionId,
            String sourceServer,
            String targetServer,
            String remoteAddress) {
        if (success && player != null && !player.isBlank()) {
            updatePlayerServer(connectionKey(player.trim(), connectionId), targetServer, remoteAddress);
        }
    }
    public long currentEventLoopDelayNanos() { return eventLoopDelayNanos.get(); }
    public void eventLoopDelayNanos(long nanos) { eventLoopDelayNanos.set(Math.max(0, nanos)); }
    public Optional<PlayerSession> findPlayerSession(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(currentConnectionByPlayer.get(playerKey(playerName)))
                .map(playerSessionsByConnection::get);
    }
    public Optional<PlayerSession> findPlayerSession(dev.strataproxy.plugin.service.PlayerIdentity identity) {
        if (identity == null || identity.connectionId().isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(playerSessionsByConnection.get(identity.connectionId()))
                .filter(session -> identity.uuid() == null || identity.uuid().equals(session.playerId()));
    }
    public Collection<PlayerSession> onlinePlayerSessions() {
        return List.copyOf(playerSessionsByConnection.values());
    }
    public int onlinePlayerCount() {
        return playerSessionsByConnection.size();
    }
    public Snapshot snapshot() {
        return new Snapshot(acceptedConnections.sum(), activeConnections.get(), rejectedConnections.sum(), immutableLongs(rejectedByReason),
                handshakeTimeouts.sum(), routedConnections.sum(), failedRoutes.sum(), backendConnectFailures.sum(), immutableLongs(backendReplacements),
                frontendToBackendBytes.sum(), backendToFrontendBytes.sum(), eventLoopDelayNanos.get(),
                serverTraffic.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().snapshot())),
                serverConnections.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().snapshot())),
                currentPlayerSessions());
    }

    private MutableServerTraffic serverTraffic(String server) { return serverTraffic.computeIfAbsent(normalize(server), ignored -> new MutableServerTraffic()); }
    private static Map<String, Long> immutableLongs(Map<String, LongAdder> values) {
        return values.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().sum()));
    }
    private void updatePlayerServer(String connectionKey, String targetServer, String remoteAddress) {
        if (connectionKey == null) {
            return;
        }
        playerSessionsByConnection.computeIfPresent(connectionKey, (ignored, session) -> new PlayerSession(
                session.player(), session.playerId(), session.connectionId(), normalize(targetServer), normalize(remoteAddress)));
    }
    private Map<String, PlayerSession> currentPlayerSessions() {
        var sessions = new java.util.HashMap<String, PlayerSession>();
        currentConnectionByPlayer.forEach((player, connectionId) -> {
            var session = playerSessionsByConnection.get(connectionId);
            if (session != null) {
                sessions.put(session.player(), session);
            }
        });
        return Map.copyOf(sessions);
    }
    private static String connectionKey(String player, String connectionId) {
        return connectionId == null || connectionId.isBlank() ? "legacy:" + playerKey(player) : connectionId;
    }
    private static String playerKey(String player) {
        return player.trim().toLowerCase(Locale.ROOT);
    }
    private static String normalize(String value) { return value == null || value.isBlank() ? "unknown" : value.trim(); }

    public static final class ServerTrafficRecorder {
        private final ProxyMetrics metrics; private final String server;
        private ServerTrafficRecorder(ProxyMetrics metrics, String server) { this.metrics = metrics; this.server = server; }
        public void frontendToBackendBytes(long bytes) { metrics.frontendToBackendBytes(server, bytes); }
        public void backendToFrontendBytes(long bytes) { metrics.backendToFrontendBytes(server, bytes); }
    }
    public record ServerTraffic(long frontendToBackendBytes, long backendToFrontendBytes) { }
    public record ServerConnections(long activeConnections, long routedConnections) { }
    public record PlayerSession(String player, java.util.UUID playerId, String connectionId, String server, String remoteAddress) { }
    public record Snapshot(long acceptedConnections, long activeConnections, long rejectedConnections,
                           Map<String, Long> rejectedConnectionsByReason, long handshakeTimeouts, long routedConnections,
                           long failedRoutes, long backendConnectFailures, Map<String, Long> backendReplacements,
                           long frontendToBackendBytes, long backendToFrontendBytes, long eventLoopDelayNanos,
                           Map<String, ServerTraffic> serverTraffic, Map<String, ServerConnections> serverConnections,
                           Map<String, PlayerSession> playerSessions) { }
    private static final class MutableServerTraffic {
        private final LongAdder frontendToBackendBytes = new LongAdder(); private final LongAdder backendToFrontendBytes = new LongAdder();
        private ServerTraffic snapshot() { return new ServerTraffic(frontendToBackendBytes.sum(), backendToFrontendBytes.sum()); }
    }
    private static final class MutableServerConnections {
        private final AtomicLong activeConnections = new AtomicLong(); private final LongAdder routedConnections = new LongAdder();
        private ServerConnections snapshot() { return new ServerConnections(activeConnections.get(), routedConnections.sum()); }
    }
}

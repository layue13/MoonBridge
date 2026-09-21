package dev.strataproxy.observability;

import java.util.Map;
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
    private final Map<String, PlayerSession> playerSessions = new ConcurrentHashMap<>();

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
        serverConnections.computeIfAbsent(normalize(server), ignored -> new MutableServerConnections())
                .activeConnections.updateAndGet(value -> Math.max(0, value - 1));
    }
    public void playerSessionStarted(String player, String server, String remoteAddress) {
        if (player != null && !player.isBlank()) playerSessions.put(player.trim(), new PlayerSession(player.trim(), normalize(server), normalize(remoteAddress)));
    }
    public void playerSessionClosed(String player) {
        if (player != null && !player.isBlank()) playerSessions.remove(player.trim());
    }
    public void playerTransfer(boolean success, String outcome, String player, String sourceServer, String targetServer, String remoteAddress) {
        if (success && player != null && !player.isBlank()) playerSessions.put(player.trim(), new PlayerSession(player.trim(), normalize(targetServer), normalize(remoteAddress)));
    }
    public long currentEventLoopDelayNanos() { return eventLoopDelayNanos.get(); }
    public void eventLoopDelayNanos(long nanos) { eventLoopDelayNanos.set(Math.max(0, nanos)); }
    public void pooledDirectMemoryBytes(long bytes) { }
    public void networkTransport(String name, boolean nativeTransport) { }
    public void nativeRuntime(boolean enabled, String os, String arch, String detectionSource, String tlsProvider,
                              String compressionProvider, boolean preferNativeTransport, boolean requireNativeTransport,
                              Map<String, Boolean> features) { }

    public void compressionNegotiated(String server, int threshold) { }
    public void compressionDecision(String server, CompressionDirection direction, String action, int threshold) { }
    public void compressionRewrite(String server, CompressionDirection direction, String outcome) { }
    public void compressionRewrite(String server, CompressionDirection direction, String outcome, long cpuNanos) { }

    public Snapshot snapshot() {
        return new Snapshot(acceptedConnections.sum(), activeConnections.get(), rejectedConnections.sum(), immutableLongs(rejectedByReason),
                handshakeTimeouts.sum(), routedConnections.sum(), failedRoutes.sum(), backendConnectFailures.sum(), immutableLongs(backendReplacements),
                frontendToBackendBytes.sum(), backendToFrontendBytes.sum(), eventLoopDelayNanos.get(),
                serverTraffic.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().snapshot())),
                serverConnections.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().snapshot())),
                Map.copyOf(playerSessions));
    }

    private MutableServerTraffic serverTraffic(String server) { return serverTraffic.computeIfAbsent(normalize(server), ignored -> new MutableServerTraffic()); }
    private static Map<String, Long> immutableLongs(Map<String, LongAdder> values) {
        return values.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().sum()));
    }
    private static String normalize(String value) { return value == null || value.isBlank() ? "unknown" : value.trim(); }

    public enum CompressionDirection {
        FRONTEND_TO_BACKEND("frontend_to_backend"),
        BACKEND_TO_FRONTEND("backend_to_frontend");

        private final String label;
        CompressionDirection(String label) { this.label = label; }
        public String label() { return label; }
    }
    public static final class ServerTrafficRecorder {
        private final ProxyMetrics metrics; private final String server;
        private ServerTrafficRecorder(ProxyMetrics metrics, String server) { this.metrics = metrics; this.server = server; }
        public void frontendToBackendBytes(long bytes) { metrics.frontendToBackendBytes(server, bytes); }
        public void backendToFrontendBytes(long bytes) { metrics.backendToFrontendBytes(server, bytes); }
    }
    public record ServerTraffic(long frontendToBackendBytes, long backendToFrontendBytes) { }
    public record ServerConnections(long activeConnections, long routedConnections) { }
    public record PlayerSession(String player, String server, String remoteAddress) { }
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

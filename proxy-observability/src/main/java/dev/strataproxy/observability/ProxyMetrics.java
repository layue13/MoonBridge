package dev.strataproxy.observability;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.stream.Collectors;

/**
 * Thread-safe in-process metrics accumulator used by the proxy, admin API, and diagnostics.
 */
public final class ProxyMetrics {
    private static final int RECENT_PACKET_ANOMALY_CAPACITY = 256;
    private static final int RECENT_CUSTOM_PAYLOAD_CAPACITY = 256;

    private final LongAdder acceptedConnections = new LongAdder();
    private final AtomicLong activeConnections = new AtomicLong();
    private final LongAdder rejectedConnections = new LongAdder();
    private final ConcurrentHashMap<String, LongAdder> rejectedConnectionsByReason = new ConcurrentHashMap<>();
    private final LongAdder handshakeTimeouts = new LongAdder();
    private final LongAdder routedConnections = new LongAdder();
    private final LongAdder failedRoutes = new LongAdder();
    private final LongAdder backendConnectFailures = new LongAdder();
    private final ConcurrentHashMap<String, LongAdder> backendReplacements = new ConcurrentHashMap<>();
    private final LongAdder frontendToBackendBytes = new LongAdder();
    private final LongAdder backendToFrontendBytes = new LongAdder();
    private final LongAdder compressionNegotiations = new LongAdder();
    private final AtomicLong packetAnomalySequence = new AtomicLong();
    private final AtomicReferenceArray<PacketAnomalySample> recentPacketAnomalies =
            new AtomicReferenceArray<>(RECENT_PACKET_ANOMALY_CAPACITY);
    private final AtomicLong customPayloadSequence = new AtomicLong();
    private final AtomicReferenceArray<CustomPayloadSample> recentCustomPayloads =
            new AtomicReferenceArray<>(RECENT_CUSTOM_PAYLOAD_CAPACITY);
    private final boolean packetAnomalySampling;
    private final ConcurrentHashMap<String, LongAdder> packetAnomalies = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ServerTrafficCounters> serverTraffic = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ServerConnectionCounters> serverConnections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PlayerSession> playerSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PacketTrafficKey, PacketTrafficCounters> packetTraffic = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CustomPayloadKey, CustomPayloadCounters> customPayloads = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RelayBackpressureKey, RelayBackpressureCounters> relayBackpressure = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PayloadCaptureBuffer> payloadCaptures = new ConcurrentHashMap<>();
    private final CompressionCounters compression = new CompressionCounters();
    private final ConcurrentHashMap<String, CompressionCounters> serverCompression = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CompressionDirectionKey, CompressionCounters> serverCompressionByDirection = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> serverCompressionThresholds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CompressionDecisionKey, LongAdder> compressionDecisions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CompressionRewriteKey, CompressionRewriteCounters> compressionRewrites = new ConcurrentHashMap<>();
    private final AtomicLong eventLoopDelayNanos = new AtomicLong();
    private final AtomicLong maxEventLoopDelayNanos = new AtomicLong();
    private final AtomicLong pooledDirectMemoryBytes = new AtomicLong();
    private final AtomicReference<NetworkTransport> networkTransport = new AtomicReference<>(new NetworkTransport("unknown", false));
    private final AtomicReference<NativeRuntimeInfo> nativeRuntime = new AtomicReference<>(NativeRuntimeInfo.unknown());

    /**
     * Creates metrics with packet anomaly sampling enabled.
     */
    public ProxyMetrics() {
        this(true);
    }

    /**
 * Documents this public API element.
 *
     * @param packetAnomalySampling whether recent anomaly samples should be retained
     */
    public ProxyMetrics(boolean packetAnomalySampling) {
        this.packetAnomalySampling = packetAnomalySampling;
    }

    /** Provides accepted connection. */
    public void acceptedConnection() {
        acceptedConnections.increment();
        activeConnections.incrementAndGet();
    }

    /** Provides closed connection. */
    public void closedConnection() {
        decrementGauge(activeConnections);
    }

    /** Provides rejected connection. */
    public void rejectedConnection() {
        rejectedConnection("unspecified");
    }

    /**
     * Provides rejected connection.
      * @param reason reason value
     */
    public void rejectedConnection(String reason) {
        var normalized = reason == null || reason.isBlank() ? "unspecified" : sanitize(reason.trim());
        rejectedConnections.increment();
        rejectedConnectionsByReason.computeIfAbsent(normalized, ignored -> new LongAdder()).increment();
    }

    /** Provides handshake timeout. */
    public void handshakeTimeout() {
        handshakeTimeouts.increment();
    }

    /**
     * Provides routed connection.
     */
    public void routedConnection() {
        routedConnections.increment();
    }

    /**
     * Provides server connection opened.
      * @param server server value
     */
    public void serverConnectionOpened(String server) {
        serverConnections(server).connectionOpened();
    }

    /**
     * Provides server connection closed.
      * @param server server value
     */
    public void serverConnectionClosed(String server) {
        serverConnections(server).connectionClosed();
    }

    /**
     * Provides player session started.
      * @param player player value
      * @param server server value
      * @param remoteAddress remote address value
     */
    public void playerSessionStarted(String player, String server, String remoteAddress) {
        if (player == null || player.isBlank()) {
            throw new IllegalArgumentException("player must not be blank");
        }
        if (server == null || server.isBlank()) {
            throw new IllegalArgumentException("server must not be blank");
        }
        playerSessions.put(player.trim(), new PlayerSession(
                sanitize(player.trim()),
                sanitize(server),
                sanitize(remoteAddress),
                Instant.now()));
    }

    /**
     * Provides player session closed.
      * @param player player value
     */
    public void playerSessionClosed(String player) {
        if (player != null && !player.isBlank()) {
            playerSessions.remove(player.trim());
        }
    }

    /** Provides failed route. */
    public void failedRoute() {
        failedRoutes.increment();
    }

    /**
     * Provides backend connect failure.
     */
    public void backendConnectFailure() {
        backendConnectFailures.increment();
    }

    /**
     * Provides backend replacement.
      * @param outcome outcome value
     */
    public void backendReplacement(String outcome) {
        var normalized = outcome == null || outcome.isBlank() ? "unspecified" : sanitize(outcome.trim());
        backendReplacements.computeIfAbsent(normalized, ignored -> new LongAdder()).increment();
    }

    /**
     * Provides frontend to backend bytes.
      * @param bytes bytes value
     */
    public void frontendToBackendBytes(long bytes) {
        frontendToBackendBytes.add(bytes);
    }

    /**
     * Provides backend to frontend bytes.
      * @param bytes bytes value
     */
    public void backendToFrontendBytes(long bytes) {
        backendToFrontendBytes.add(bytes);
    }

    /**
     * Provides frontend to backend bytes.
      * @param server server value
      * @param bytes bytes value
     */
    public void frontendToBackendBytes(String server, long bytes) {
        frontendToBackendBytes(bytes);
        serverTraffic(server).frontendToBackendBytes.add(bytes);
    }

    /**
     * Provides backend to frontend bytes.
      * @param server server value
      * @param bytes bytes value
     */
    public void backendToFrontendBytes(String server, long bytes) {
        backendToFrontendBytes(bytes);
        serverTraffic(server).backendToFrontendBytes.add(bytes);
    }

    /**
     * Provides packet anomaly.
      * @param rule rule value
     */
    public void packetAnomaly(String rule) {
        packetAnomaly(rule, "", "", "", "", -1, -1, -1, "");
    }

    /**
     * Provides packet anomaly.
      * @param rule rule value
      * @param remoteAddress remote address value
      * @param server server value
      * @param direction direction value
      * @param protocolState protocol state value
      * @param packetId packet id value
      * @param rawSize raw size value
      * @param compressedSize compressed size value
      * @param detail detail value
     */
    public void packetAnomaly(
            String rule,
            String remoteAddress,
            String server,
            String direction,
            String protocolState,
            int packetId,
            long rawSize,
            long compressedSize,
            String detail) {
        packetAnomalies.computeIfAbsent(rule, ignored -> new LongAdder()).increment();
        if (!packetAnomalySampling) {
            return;
        }
        var sequence = packetAnomalySequence.incrementAndGet();
        var index = (int) ((sequence - 1) % RECENT_PACKET_ANOMALY_CAPACITY);
        recentPacketAnomalies.set(index, new PacketAnomalySample(
                sequence,
                sanitize(rule),
                sanitize(remoteAddress),
                sanitize(server),
                sanitize(direction),
                sanitize(protocolState),
                packetId,
                rawSize,
                compressedSize,
                sanitize(detail),
                Instant.now()));
    }

    /**
     * Provides packet traffic.
      * @param server server value
      * @param direction direction value
      * @param protocolState protocol state value
      * @param packetId packet id value
      * @param rawBytes raw bytes value
      * @param compressedBytes compressed bytes value
     */
    public void packetTraffic(
            String server,
            CompressionDirection direction,
            String protocolState,
            int packetId,
            long rawBytes,
            long compressedBytes) {
        if (server == null || server.isBlank()) {
            throw new IllegalArgumentException("server must not be blank");
        }
        if (direction == null) {
            throw new IllegalArgumentException("direction must not be null");
        }
        if (protocolState == null || protocolState.isBlank()) {
            throw new IllegalArgumentException("protocolState must not be blank");
        }
        if (rawBytes < 0 || compressedBytes < 0) {
            throw new IllegalArgumentException("packet traffic byte values must be non-negative");
        }
        packetTraffic.computeIfAbsent(
                new PacketTrafficKey(server, direction, sanitize(protocolState), packetId),
                ignored -> new PacketTrafficCounters()).add(rawBytes, compressedBytes);
    }

    /**
     * Provides custom payload.
      * @param server server value
      * @param direction direction value
      * @param kind kind value
      * @param channel channel value
      * @param payloadBytes payload bytes value
      * @param compressedBytes compressed bytes value
     */
    public void customPayload(
            String server,
            CompressionDirection direction,
            String kind,
            String channel,
            long payloadBytes,
            long compressedBytes) {
        customPayload(server, direction, kind, channel, payloadBytes, compressedBytes, "", "", "CONFIGURATION", -1);
    }

    /**
     * Provides custom payload.
      * @param server server value
      * @param direction direction value
      * @param kind kind value
      * @param channel channel value
      * @param payloadBytes payload bytes value
      * @param compressedBytes compressed bytes value
      * @param player player value
      * @param remoteAddress remote address value
      * @param protocolState protocol state value
      * @param packetId packet id value
     */
    public void customPayload(
            String server,
            CompressionDirection direction,
            String kind,
            String channel,
            long payloadBytes,
            long compressedBytes,
            String player,
            String remoteAddress,
            String protocolState,
            int packetId) {
        if (server == null || server.isBlank()) {
            throw new IllegalArgumentException("server must not be blank");
        }
        if (direction == null) {
            throw new IllegalArgumentException("direction must not be null");
        }
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind must not be blank");
        }
        if (payloadBytes < 0 || compressedBytes < 0) {
            throw new IllegalArgumentException("custom payload byte values must be non-negative");
        }
        var normalizedKind = sanitize(kind.trim());
        var normalizedChannel = normalizedChannel(normalizedKind, channel);
        customPayloads.computeIfAbsent(
                new CustomPayloadKey(server, direction, normalizedKind, normalizedChannel),
                ignored -> new CustomPayloadCounters()).add(payloadBytes, compressedBytes);
        var sequence = customPayloadSequence.incrementAndGet();
        var index = (int) ((sequence - 1) % RECENT_CUSTOM_PAYLOAD_CAPACITY);
        recentCustomPayloads.set(index, new CustomPayloadSample(
                sequence,
                sanitize(server),
                direction,
                normalizedKind,
                normalizedChannel,
                Math.max(0, payloadBytes),
                Math.max(0, compressedBytes),
                sanitize(player),
                sanitize(remoteAddress),
                sanitize(protocolState),
                packetId,
                Instant.now()));
    }

    /**
     * Provides relay backpressure.
      * @param server server value
      * @param direction direction value
      * @param bytesBeforeWritable bytes before writable value
     */
    public void relayBackpressure(String server, CompressionDirection direction, long bytesBeforeWritable) {
        if (server == null || server.isBlank()) {
            throw new IllegalArgumentException("server must not be blank");
        }
        if (direction == null) {
            throw new IllegalArgumentException("direction must not be null");
        }
        relayBackpressure.computeIfAbsent(
                new RelayBackpressureKey(server, direction),
                ignored -> new RelayBackpressureCounters()).record(Math.max(0, bytesBeforeWritable));
    }

    /**
     * Provides start payload capture.
      * @param id id value
      * @param server server value
      * @param direction direction value
      * @param maxSamples max samples value
      * @param maxBytesPerSample max bytes per sample value
      * @param expiresAt expires at value
      * @return result of the operation
     */
    public PayloadCapture startPayloadCapture(
            String id,
            String server,
            CompressionDirection direction,
            int maxSamples,
            int maxBytesPerSample,
            Instant expiresAt) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("payload capture id must not be blank");
        }
        if (server == null || server.isBlank()) {
            throw new IllegalArgumentException("payload capture server must not be blank");
        }
        if (direction == null) {
            throw new IllegalArgumentException("payload capture direction must not be null");
        }
        if (maxSamples <= 0) {
            throw new IllegalArgumentException("payload capture maxSamples must be positive");
        }
        if (maxBytesPerSample <= 0) {
            throw new IllegalArgumentException("payload capture maxBytesPerSample must be positive");
        }
        if (expiresAt == null || !expiresAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("payload capture expiresAt must be in the future");
        }
        var capture = new PayloadCapture(id.trim(), server.trim(), direction, maxSamples, maxBytesPerSample, expiresAt);
        payloadCaptures.put(capture.id(), new PayloadCaptureBuffer(capture));
        return capture;
    }

    /**
     * Provides stop payload capture.
      * @param id id value
      * @return result of the operation
     */
    public boolean stopPayloadCapture(String id) {
        return id != null && payloadCaptures.remove(id) != null;
    }

    /**
     * Provides payload captures.
      * @return result of the operation
     */
    public List<PayloadCapture> payloadCaptures() {
        expirePayloadCaptures();
        return payloadCaptures.values().stream()
                .map(PayloadCaptureBuffer::capture)
                .sorted(Comparator.comparing(PayloadCapture::id))
                .toList();
    }

    /**
     * Provides payload capture samples.
      * @param id id value
      * @return result of the operation
     */
    public List<PayloadCaptureSample> payloadCaptureSamples(String id) {
        expirePayloadCaptures();
        var capture = payloadCaptures.get(id);
        return capture == null ? List.of() : capture.samples();
    }

    /**
     * Provides payload capture request.
      * @param server server value
      * @param direction direction value
      * @return result of the operation
     */
    public PayloadCaptureRequest payloadCaptureRequest(String server, CompressionDirection direction) {
        if (server == null || server.isBlank() || direction == null || payloadCaptures.isEmpty()) {
            return PayloadCaptureRequest.none();
        }
        var now = Instant.now();
        var maxBytes = 0;
        for (var entry : payloadCaptures.entrySet()) {
            var capture = entry.getValue().capture();
            if (!capture.expiresAt().isAfter(now)) {
                payloadCaptures.remove(entry.getKey(), entry.getValue());
                continue;
            }
            if (capture.server().equals(server) && capture.direction() == direction) {
                maxBytes = Math.max(maxBytes, capture.maxBytesPerSample());
            }
        }
        return maxBytes <= 0 ? PayloadCaptureRequest.none() : new PayloadCaptureRequest(true, maxBytes);
    }

    /**
     * Provides payload captured.
      * @param server server value
      * @param direction direction value
      * @param rawBytes raw bytes value
      * @param compressedBytes compressed bytes value
      * @param prefixBytes prefix bytes value
     */
    public void payloadCaptured(
            String server,
            CompressionDirection direction,
            long rawBytes,
            long compressedBytes,
            byte[] prefixBytes) {
        payloadCaptured(server, direction, rawBytes, compressedBytes, prefixBytes, "", "");
    }

    /**
     * Provides payload captured.
      * @param server server value
      * @param direction direction value
      * @param rawBytes raw bytes value
      * @param compressedBytes compressed bytes value
      * @param prefixBytes prefix bytes value
      * @param player player value
      * @param remoteAddress remote address value
     */
    public void payloadCaptured(
            String server,
            CompressionDirection direction,
            long rawBytes,
            long compressedBytes,
            byte[] prefixBytes,
            String player,
            String remoteAddress) {
        if (server == null || server.isBlank() || direction == null || prefixBytes == null || payloadCaptures.isEmpty()) {
            return;
        }
        var now = Instant.now();
        for (var entry : payloadCaptures.entrySet()) {
            var buffer = entry.getValue();
            var capture = buffer.capture();
            if (!capture.expiresAt().isAfter(now)) {
                payloadCaptures.remove(entry.getKey(), buffer);
                continue;
            }
            if (capture.server().equals(server) && capture.direction() == direction) {
                var limit = Math.min(capture.maxBytesPerSample(), prefixBytes.length);
                buffer.record(rawBytes, compressedBytes, Arrays.copyOf(prefixBytes, limit), player, remoteAddress, now);
            }
        }
    }

    /**
     * Provides compression sample.
      * @param rawBytes raw bytes value
      * @param compressedBytes compressed bytes value
      * @param cpuNanos cpu nanos value
     */
    public void compressionSample(long rawBytes, long compressedBytes, long cpuNanos) {
        compression.add(rawBytes, compressedBytes, cpuNanos);
    }

    /**
     * Provides compression sample.
      * @param server server value
      * @param rawBytes raw bytes value
      * @param compressedBytes compressed bytes value
      * @param cpuNanos cpu nanos value
     */
    public void compressionSample(String server, long rawBytes, long compressedBytes, long cpuNanos) {
        compressionSample(rawBytes, compressedBytes, cpuNanos);
        serverCompression(server).add(rawBytes, compressedBytes, cpuNanos);
    }

    /**
     * Provides compression sample.
      * @param server server value
      * @param direction direction value
      * @param rawBytes raw bytes value
      * @param compressedBytes compressed bytes value
      * @param cpuNanos cpu nanos value
     */
    public void compressionSample(String server, CompressionDirection direction, long rawBytes, long compressedBytes, long cpuNanos) {
        compressionSample(server, rawBytes, compressedBytes, cpuNanos);
        serverCompression(server, direction).add(rawBytes, compressedBytes, cpuNanos);
    }

    /**
     * Provides compression negotiated.
      * @param server server value
      * @param threshold threshold value
     */
    public void compressionNegotiated(String server, int threshold) {
        if (threshold < 0) {
            throw new IllegalArgumentException("compression threshold must be non-negative");
        }
        compressionNegotiations.increment();
        serverCompressionThreshold(server).set(threshold);
    }

    /**
     * Provides compression decision.
      * @param server server value
      * @param direction direction value
      * @param action action value
      * @param threshold threshold value
     */
    public void compressionDecision(String server, CompressionDirection direction, String action, int threshold) {
        if (server == null || server.isBlank()) {
            throw new IllegalArgumentException("server must not be blank");
        }
        if (direction == null) {
            throw new IllegalArgumentException("direction must not be null");
        }
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action must not be blank");
        }
        compressionDecisions.computeIfAbsent(
                new CompressionDecisionKey(server, direction, action.trim(), threshold),
                ignored -> new LongAdder()).increment();
    }

    /**
     * Provides compression rewrite.
      * @param server server value
      * @param direction direction value
      * @param outcome outcome value
     */
    public void compressionRewrite(String server, CompressionDirection direction, String outcome) {
        compressionRewrite(server, direction, outcome, 0);
    }

    /**
     * Provides compression rewrite.
      * @param server server value
      * @param direction direction value
      * @param outcome outcome value
      * @param cpuNanos cpu nanos value
     */
    public void compressionRewrite(String server, CompressionDirection direction, String outcome, long cpuNanos) {
        if (server == null || server.isBlank()) {
            throw new IllegalArgumentException("server must not be blank");
        }
        if (direction == null) {
            throw new IllegalArgumentException("direction must not be null");
        }
        if (outcome == null || outcome.isBlank()) {
            throw new IllegalArgumentException("outcome must not be blank");
        }
        if (cpuNanos < 0) {
            throw new IllegalArgumentException("compression rewrite cpuNanos must be non-negative");
        }
        compressionRewrites.computeIfAbsent(
                new CompressionRewriteKey(server, direction, sanitize(outcome.trim())),
                ignored -> new CompressionRewriteCounters()).add(cpuNanos);
    }

    /**
     * Provides current event loop delay nanos.
      * @return result of the operation
     */
    public long currentEventLoopDelayNanos() {
        return eventLoopDelayNanos.get();
    }

    /**
     * Provides event loop delay nanos.
      * @param nanos nanos value
     */
    public void eventLoopDelayNanos(long nanos) {
        eventLoopDelayNanos.set(Math.max(0, nanos));
        maxEventLoopDelayNanos.accumulateAndGet(Math.max(0, nanos), Math::max);
    }

    /**
     * Provides pooled direct memory bytes.
      * @param bytes bytes value
     */
    public void pooledDirectMemoryBytes(long bytes) {
        pooledDirectMemoryBytes.set(Math.max(0, bytes));
    }

    /**
     * Provides network transport.
      * @param name name value
      * @param nativeTransport native transport value
     */
    public void networkTransport(String name, boolean nativeTransport) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("transport name must not be blank");
        }
        networkTransport.set(new NetworkTransport(name, nativeTransport));
    }

    /**
     * Provides native runtime.
      * @param enabled enabled value
      * @param os os value
      * @param arch arch value
      * @param detectionSource detection source value
      * @param tlsProvider tls provider value
      * @param compressionProvider compression provider value
      * @param preferNativeTransport prefer native transport value
      * @param requireNativeTransport require native transport value
      * @param features features value
     */
    public void nativeRuntime(
            boolean enabled,
            String os,
            String arch,
            String detectionSource,
            String tlsProvider,
            String compressionProvider,
            boolean preferNativeTransport,
            boolean requireNativeTransport,
            Map<String, Boolean> features) {
        nativeRuntime.set(new NativeRuntimeInfo(
                enabled,
                sanitize(os),
                sanitize(arch),
                sanitize(detectionSource),
                sanitize(tlsProvider),
                sanitize(compressionProvider),
                preferNativeTransport,
                requireNativeTransport,
                features == null ? Map.of() : Map.copyOf(features)));
    }

    /**
     * Provides snapshot.
      * @return result of the operation
     */
    public Snapshot snapshot() {
        expirePayloadCaptures();
        return new Snapshot(
                acceptedConnections.sum(),
                activeConnections.get(),
                rejectedConnections.sum(),
                rejectedConnectionsByReason.entrySet().stream()
                        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().sum())),
                handshakeTimeouts.sum(),
                routedConnections.sum(),
                failedRoutes.sum(),
                backendConnectFailures.sum(),
                backendReplacements.entrySet().stream()
                        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().sum())),
                frontendToBackendBytes.sum(),
                backendToFrontendBytes.sum(),
                compressionNegotiations.sum(),
                eventLoopDelayNanos.get(),
                maxEventLoopDelayNanos.get(),
                pooledDirectMemoryBytes.get(),
                networkTransport.get(),
                nativeRuntime.get(),
                packetAnomalies.entrySet().stream()
                        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().sum())),
                recentPacketAnomalySamples(),
                serverTraffic.entrySet().stream()
                        .collect(Collectors.toUnmodifiableMap(
                                Map.Entry::getKey,
                                entry -> entry.getValue().snapshot())),
                serverConnections.entrySet().stream()
                        .collect(Collectors.toUnmodifiableMap(
                                Map.Entry::getKey,
                                entry -> entry.getValue().snapshot())),
                Map.copyOf(playerSessions),
                packetTraffic.entrySet().stream()
                        .collect(Collectors.toUnmodifiableMap(
                                Map.Entry::getKey,
                                entry -> entry.getValue().snapshot())),
                customPayloads.entrySet().stream()
                        .collect(Collectors.toUnmodifiableMap(
                                Map.Entry::getKey,
                                entry -> entry.getValue().snapshot())),
                recentCustomPayloadSamples(),
                relayBackpressure.entrySet().stream()
                        .collect(Collectors.toUnmodifiableMap(
                                Map.Entry::getKey,
                                entry -> entry.getValue().snapshot())),
                compression.snapshot(),
                serverCompression.entrySet().stream()
                        .collect(Collectors.toUnmodifiableMap(
                                Map.Entry::getKey,
                                entry -> entry.getValue().snapshot())),
                serverCompressionByDirection.entrySet().stream()
                        .collect(Collectors.toUnmodifiableMap(
                                Map.Entry::getKey,
                                entry -> entry.getValue().snapshot())),
                serverCompressionThresholds.entrySet().stream()
                        .collect(Collectors.toUnmodifiableMap(
                                Map.Entry::getKey,
                                entry -> entry.getValue().get())),
                compressionDecisions.entrySet().stream()
                        .collect(Collectors.toUnmodifiableMap(
                                Map.Entry::getKey,
                                entry -> entry.getValue().sum())),
                compressionRewrites.entrySet().stream()
                        .collect(Collectors.toUnmodifiableMap(
                                Map.Entry::getKey,
                                entry -> entry.getValue().snapshot())),
                payloadCaptures(),
                payloadCaptures.entrySet().stream()
                        .collect(Collectors.toUnmodifiableMap(
                                Map.Entry::getKey,
                                entry -> entry.getValue().samples())));
    }

    private void expirePayloadCaptures() {
        var now = Instant.now();
        for (var entry : payloadCaptures.entrySet()) {
            if (!entry.getValue().capture().expiresAt().isAfter(now)) {
                payloadCaptures.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    private List<PacketAnomalySample> recentPacketAnomalySamples() {
        var samples = new ArrayList<PacketAnomalySample>(RECENT_PACKET_ANOMALY_CAPACITY);
        for (var i = 0; i < recentPacketAnomalies.length(); i++) {
            var sample = recentPacketAnomalies.get(i);
            if (sample != null) {
                samples.add(sample);
            }
        }
        samples.sort(Comparator.comparingLong(PacketAnomalySample::sequence).reversed());
        return List.copyOf(samples);
    }

    private List<CustomPayloadSample> recentCustomPayloadSamples() {
        var samples = new ArrayList<CustomPayloadSample>(RECENT_CUSTOM_PAYLOAD_CAPACITY);
        for (var i = 0; i < recentCustomPayloads.length(); i++) {
            var sample = recentCustomPayloads.get(i);
            if (sample != null) {
                samples.add(sample);
            }
        }
        samples.sort(Comparator.comparingLong(CustomPayloadSample::sequence).reversed());
        return List.copyOf(samples);
    }

    private static String sanitize(String value) {
        return value == null ? "" : value;
    }

    private static String normalizedChannel(String kind, String channel) {
        if ("UNKNOWN".equals(kind)) {
            return "unknown";
        }
        var value = channel == null || channel.isBlank() ? "unknown" : sanitize(channel.trim());
        return value.length() <= 128 ? value : value.substring(0, 128);
    }

    private static void decrementGauge(AtomicLong gauge) {
        gauge.updateAndGet(value -> Math.max(0, value - 1));
    }

    private ServerTrafficCounters serverTraffic(String server) {
        if (server == null || server.isBlank()) {
            throw new IllegalArgumentException("server must not be blank");
        }
        return serverTraffic.computeIfAbsent(server, ignored -> new ServerTrafficCounters());
    }

    private ServerConnectionCounters serverConnections(String server) {
        if (server == null || server.isBlank()) {
            throw new IllegalArgumentException("server must not be blank");
        }
        return serverConnections.computeIfAbsent(server, ignored -> new ServerConnectionCounters());
    }

    private CompressionCounters serverCompression(String server) {
        if (server == null || server.isBlank()) {
            throw new IllegalArgumentException("server must not be blank");
        }
        return serverCompression.computeIfAbsent(server, ignored -> new CompressionCounters());
    }

    private CompressionCounters serverCompression(String server, CompressionDirection direction) {
        if (server == null || server.isBlank()) {
            throw new IllegalArgumentException("server must not be blank");
        }
        if (direction == null) {
            throw new IllegalArgumentException("direction must not be null");
        }
        return serverCompressionByDirection.computeIfAbsent(new CompressionDirectionKey(server, direction), ignored -> new CompressionCounters());
    }

    private AtomicInteger serverCompressionThreshold(String server) {
        if (server == null || server.isBlank()) {
            throw new IllegalArgumentException("server must not be blank");
        }
        return serverCompressionThresholds.computeIfAbsent(server, ignored -> new AtomicInteger(-1));
    }

    private static final class ServerTrafficCounters {
        private final LongAdder frontendToBackendBytes = new LongAdder();
        private final LongAdder backendToFrontendBytes = new LongAdder();

        private ServerTraffic snapshot() {
            return new ServerTraffic(frontendToBackendBytes.sum(), backendToFrontendBytes.sum());
        }
    }

    /**
     * Per-server byte counters split by relay direction.
 * @param frontendToBackendBytes frontend to backend bytes
 * @param backendToFrontendBytes backend to frontend bytes
 */
    public record ServerTraffic(long frontendToBackendBytes, long backendToFrontendBytes) {
    }

    private static final class ServerConnectionCounters {
        private final LongAdder routedConnections = new LongAdder();
        private final AtomicLong activeConnections = new AtomicLong();

        private void connectionOpened() {
            routedConnections.increment();
            activeConnections.incrementAndGet();
        }

        private void connectionClosed() {
            decrementGauge(activeConnections);
        }

        private ServerConnections snapshot() {
            return new ServerConnections(routedConnections.sum(), activeConnections.get());
        }
    }

    /**
     * Per-server routed and active connection counters.
 * @param routedConnections routed connections
 * @param activeConnections active connections
 */
    public record ServerConnections(long routedConnections, long activeConnections) {
    }

    /**
     * Active player session tracked by the relay.
 * @param player player
 * @param server server
 * @param remoteAddress remote address
 * @param connectedAt connected at
 */
    public record PlayerSession(String player, String server, String remoteAddress, Instant connectedAt) {
    }

    private static final class PacketTrafficCounters {
        private final LongAdder packets = new LongAdder();
        private final LongAdder rawBytes = new LongAdder();
        private final LongAdder compressedBytes = new LongAdder();

        private void add(long rawBytes, long compressedBytes) {
            packets.increment();
            this.rawBytes.add(rawBytes);
            this.compressedBytes.add(compressedBytes);
        }

        private PacketTraffic snapshot() {
            return new PacketTraffic(packets.sum(), rawBytes.sum(), compressedBytes.sum());
        }
    }

    /**
     * Aggregated packet traffic counters.
 * @param packets packets
 * @param rawBytes raw bytes
 * @param compressedBytes compressed bytes
 */
    public record PacketTraffic(long packets, long rawBytes, long compressedBytes) {
    }

    private static final class CustomPayloadCounters {
        private final LongAdder packets = new LongAdder();
        private final LongAdder payloadBytes = new LongAdder();
        private final LongAdder compressedBytes = new LongAdder();
        private final AtomicLong maxPayloadBytes = new AtomicLong();
        private final AtomicLong maxCompressedBytes = new AtomicLong();
        private final AtomicReference<Instant> firstSeen = new AtomicReference<>();
        private final AtomicReference<Instant> lastSeen = new AtomicReference<>();

        private void add(long payloadBytes, long compressedBytes) {
            packets.increment();
            this.payloadBytes.add(payloadBytes);
            this.compressedBytes.add(compressedBytes);
            maxPayloadBytes.accumulateAndGet(payloadBytes, Math::max);
            maxCompressedBytes.accumulateAndGet(compressedBytes, Math::max);
            var now = Instant.now();
            firstSeen.compareAndSet(null, now);
            lastSeen.set(now);
        }

        private CustomPayloadTraffic snapshot() {
            return new CustomPayloadTraffic(
                    packets.sum(),
                    payloadBytes.sum(),
                    compressedBytes.sum(),
                    maxPayloadBytes.get(),
                    maxCompressedBytes.get(),
                    firstSeen.get(),
                    lastSeen.get());
        }
    }

    /**
     * Aggregated custom payload traffic counters.
 * @param packets packets
 * @param payloadBytes payload bytes
 * @param compressedBytes compressed bytes
 * @param maxPayloadBytes max payload bytes
 * @param maxCompressedBytes max compressed bytes
 * @param firstSeen first seen
 * @param lastSeen last seen
 */
    public record CustomPayloadTraffic(
            long packets,
            long payloadBytes,
            long compressedBytes,
            long maxPayloadBytes,
            long maxCompressedBytes,
            Instant firstSeen,
            Instant lastSeen) {
    }

    /**
     * Recent custom payload sample retained for diagnostics.
 * @param sequence sequence
 * @param server server
 * @param direction direction
 * @param kind kind
 * @param channel channel
 * @param payloadBytes payload bytes
 * @param compressedBytes compressed bytes
 * @param player player
 * @param remoteAddress remote address
 * @param protocolState protocol state
 * @param packetId packet id
 * @param timestamp timestamp
 */
    public record CustomPayloadSample(
            long sequence,
            String server,
            CompressionDirection direction,
            String kind,
            String channel,
            long payloadBytes,
            long compressedBytes,
            String player,
            String remoteAddress,
            String protocolState,
            int packetId,
            Instant timestamp) {
    }

    private static final class RelayBackpressureCounters {
        private final LongAdder events = new LongAdder();
        private final AtomicLong lastBytesBeforeWritable = new AtomicLong();
        private final AtomicLong maxBytesBeforeWritable = new AtomicLong();

        private void record(long bytesBeforeWritable) {
            events.increment();
            lastBytesBeforeWritable.set(bytesBeforeWritable);
            maxBytesBeforeWritable.accumulateAndGet(bytesBeforeWritable, Math::max);
        }

        private RelayBackpressure snapshot() {
            return new RelayBackpressure(events.sum(), lastBytesBeforeWritable.get(), maxBytesBeforeWritable.get());
        }
    }

    /**
     * Aggregated relay backpressure counters.
 * @param events events
 * @param lastBytesBeforeWritable last bytes before writable
 * @param maxBytesBeforeWritable max bytes before writable
 */
    public record RelayBackpressure(long events, long lastBytesBeforeWritable, long maxBytesBeforeWritable) {
    }

    private static final class PayloadCaptureBuffer {
        private final PayloadCapture capture;
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicReferenceArray<PayloadCaptureSample> samples;

        private PayloadCaptureBuffer(PayloadCapture capture) {
            this.capture = capture;
            this.samples = new AtomicReferenceArray<>(capture.maxSamples());
        }

        private PayloadCapture capture() {
            return capture;
        }

        private void record(
                long rawBytes,
                long compressedBytes,
                byte[] prefixBytes,
                String player,
                String remoteAddress,
                Instant timestamp) {
            var next = sequence.incrementAndGet();
            var index = (int) ((next - 1) % samples.length());
            samples.set(index, new PayloadCaptureSample(
                    next,
                    capture.id(),
                    capture.server(),
                    capture.direction(),
                    Math.max(0, rawBytes),
                    Math.max(0, compressedBytes),
                    prefixBytes,
                    sanitize(player),
                    sanitize(remoteAddress),
                    timestamp));
        }

        private List<PayloadCaptureSample> samples() {
            var result = new ArrayList<PayloadCaptureSample>(samples.length());
            for (var i = 0; i < samples.length(); i++) {
                var sample = samples.get(i);
                if (sample != null) {
                    result.add(sample);
                }
            }
            result.sort(Comparator.comparingLong(PayloadCaptureSample::sequence).reversed());
            return List.copyOf(result);
        }
    }

    /**
     * Active payload capture configuration.
 * @param id id
 * @param server server
 * @param direction direction
 * @param maxSamples max samples
 * @param maxBytesPerSample max bytes per sample
 * @param expiresAt expires at
 */
    public record PayloadCapture(
            String id,
            String server,
            CompressionDirection direction,
            int maxSamples,
            int maxBytesPerSample,
            Instant expiresAt) {
    }

    /**
     * Per-key payload capture request state.
 * @param enabled enabled
 * @param maxBytesPerSample max bytes per sample
 */
    public record PayloadCaptureRequest(boolean enabled, int maxBytesPerSample) {
        private static PayloadCaptureRequest none() {
            return new PayloadCaptureRequest(false, 0);
        }
    }

    /**
     * Captured payload prefix sample.
 * @param sequence sequence
 * @param captureId capture id
 * @param server server
 * @param direction direction
 * @param rawBytes raw bytes
 * @param compressedBytes compressed bytes
 * @param prefixBytes prefix bytes
 * @param player player
 * @param remoteAddress remote address
 * @param timestamp timestamp
 */
    public record PayloadCaptureSample(
            long sequence,
            String captureId,
            String server,
            CompressionDirection direction,
            long rawBytes,
            long compressedBytes,
            byte[] prefixBytes,
            String player,
            String remoteAddress,
            Instant timestamp) {
    }

    private static final class CompressionCounters {
        private final LongAdder samples = new LongAdder();
        private final LongAdder rawBytes = new LongAdder();
        private final LongAdder compressedBytes = new LongAdder();
        private final LongAdder cpuNanos = new LongAdder();

        private void add(long rawBytes, long compressedBytes, long cpuNanos) {
            if (rawBytes < 0 || compressedBytes < 0 || cpuNanos < 0) {
                throw new IllegalArgumentException("compression sample values must be non-negative");
            }
            samples.increment();
            this.rawBytes.add(rawBytes);
            this.compressedBytes.add(compressedBytes);
            this.cpuNanos.add(cpuNanos);
        }

        private CompressionAudit snapshot() {
            var raw = rawBytes.sum();
            var compressed = compressedBytes.sum();
            return new CompressionAudit(samples.sum(), raw, compressed, Math.max(0, raw - compressed), cpuNanos.sum());
        }
    }

    /**
     * Compression byte and CPU accounting.
 * @param samples samples
 * @param rawBytes raw bytes
 * @param compressedBytes compressed bytes
 * @param savedBytes saved bytes
 * @param cpuNanos cpu nanos
 */
    public record CompressionAudit(
            long samples,
            long rawBytes,
            long compressedBytes,
            long savedBytes,
            long cpuNanos) {
        /**
         * Provides ratio.
          * @return result of the operation
         */
        public double ratio() {
            return rawBytes == 0 ? 1.0d : (double) compressedBytes / rawBytes;
        }
    }

    /**
     * Network transport currently selected by the server.
 * @param name name
 * @param nativeTransport native transport
 */
    public record NetworkTransport(String name, boolean nativeTransport) {
    }

    /**
     * Native runtime state reported in metrics snapshots.
 * @param enabled enabled
 * @param os os
 * @param arch arch
 * @param detectionSource detection source
 * @param tlsProvider tls provider
 * @param compressionProvider compression provider
 * @param preferNativeTransport prefer native transport
 * @param requireNativeTransport require native transport
 * @param features features
 */
    public record NativeRuntimeInfo(
            boolean enabled,
            String os,
            String arch,
            String detectionSource,
            String tlsProvider,
            String compressionProvider,
            boolean preferNativeTransport,
            boolean requireNativeTransport,
            Map<String, Boolean> features) {
        private static NativeRuntimeInfo unknown() {
            return new NativeRuntimeInfo(false, "unknown", "unknown", "unknown", "jdk", "jdk-deflater", false, false, Map.of());
        }
    }

    /**
     * Recent packet anomaly sample retained for diagnostics.
 * @param sequence sequence
 * @param rule rule
 * @param remoteAddress remote address
 * @param server server
 * @param direction direction
 * @param protocolState protocol state
 * @param packetId packet id
 * @param rawSize raw size
 * @param compressedSize compressed size
 * @param detail detail
 * @param timestamp timestamp
 */
    public record PacketAnomalySample(
            long sequence,
            String rule,
            String remoteAddress,
            String server,
            String direction,
            String protocolState,
            int packetId,
            long rawSize,
            long compressedSize,
            String detail,
            Instant timestamp) {
    }

    /**
     * Relay direction used by compression and traffic metrics.
     */
    public enum CompressionDirection {
        /**
         * Enum constant for frontend to backend.
         */
        FRONTEND_TO_BACKEND("frontend_to_backend"),
        /**
         * Enum constant for backend to frontend.
         */
        BACKEND_TO_FRONTEND("backend_to_frontend");

        private final String label;

        CompressionDirection(String label) {
            this.label = label;
        }

        /**
         * Provides label.
          * @return result of the operation
         */
        public String label() {
            return label;
        }
    }

    /**
     * Map key for per-server compression metrics split by direction.
 * @param server server
 * @param direction direction
 */
    public record CompressionDirectionKey(String server, CompressionDirection direction) {
    }

    /**
     * Map key for compression strategy decisions.
 * @param server server
 * @param direction direction
 * @param action action
 * @param threshold threshold
 */
    public record CompressionDecisionKey(String server, CompressionDirection direction, String action, int threshold) {
    }

    /**
     * Map key for compressed-frame rewrite outcomes.
 * @param server server
 * @param direction direction
 * @param outcome outcome
 */
    public record CompressionRewriteKey(String server, CompressionDirection direction, String outcome) {
    }

    private static final class CompressionRewriteCounters {
        private final LongAdder count = new LongAdder();
        private final LongAdder cpuNanos = new LongAdder();

        private void add(long cpuNanos) {
            count.increment();
            this.cpuNanos.add(cpuNanos);
        }

        private CompressionRewrite snapshot() {
            return new CompressionRewrite(count.sum(), cpuNanos.sum());
        }
    }

    /**
     * Aggregated compressed-frame rewrite counters.
 * @param count count
 * @param cpuNanos cpu nanos
 */
    public record CompressionRewrite(long count, long cpuNanos) {
    }

    /**
     * Map key for packet traffic metrics.
 * @param server server
 * @param direction direction
 * @param protocolState protocol state
 * @param packetId packet id
 */
    public record PacketTrafficKey(String server, CompressionDirection direction, String protocolState, int packetId) {
    }

    /**
     * Map key for custom payload metrics.
 * @param server server
 * @param direction direction
 * @param kind kind
 * @param channel channel
 */
    public record CustomPayloadKey(String server, CompressionDirection direction, String kind, String channel) {
    }

    /**
     * Map key for relay backpressure metrics.
 * @param server server
 * @param direction direction
 */
    public record RelayBackpressureKey(String server, CompressionDirection direction) {
    }

    /**
     * Complete point-in-time metrics snapshot.
 * @param acceptedConnections accepted connections
 * @param activeConnections active connections
 * @param rejectedConnections rejected connections
 * @param rejectedConnectionsByReason rejected connections by reason
 * @param handshakeTimeouts handshake timeouts
 * @param routedConnections routed connections
 * @param failedRoutes failed routes
 * @param backendConnectFailures backend connect failures
 * @param backendReplacements backend replacements
 * @param frontendToBackendBytes frontend to backend bytes
 * @param backendToFrontendBytes backend to frontend bytes
 * @param compressionNegotiations compression negotiations
 * @param eventLoopDelayNanos event loop delay nanos
 * @param maxEventLoopDelayNanos max event loop delay nanos
 * @param pooledDirectMemoryBytes pooled direct memory bytes
 * @param networkTransport network transport
 * @param nativeRuntime native runtime
 * @param packetAnomalies packet anomalies
 * @param recentPacketAnomalies recent packet anomalies
 * @param serverTraffic server traffic
 * @param serverConnections server connections
 * @param playerSessions player sessions
 * @param packetTraffic packet traffic
 * @param customPayloads custom payloads
 * @param recentCustomPayloads recent custom payloads
 * @param relayBackpressure relay backpressure
 * @param compression compression
 * @param serverCompression server compression
 * @param serverCompressionByDirection server compression by direction
 * @param serverCompressionThresholds server compression thresholds
 * @param compressionDecisions compression decisions
 * @param compressionRewrites compression rewrites
 * @param payloadCaptures payload captures
 * @param payloadCaptureSamples payload capture samples
 */
    public record Snapshot(
            long acceptedConnections,
            long activeConnections,
            long rejectedConnections,
            Map<String, Long> rejectedConnectionsByReason,
            long handshakeTimeouts,
            long routedConnections,
            long failedRoutes,
            long backendConnectFailures,
            Map<String, Long> backendReplacements,
            long frontendToBackendBytes,
            long backendToFrontendBytes,
            long compressionNegotiations,
            long eventLoopDelayNanos,
            long maxEventLoopDelayNanos,
            long pooledDirectMemoryBytes,
            NetworkTransport networkTransport,
            NativeRuntimeInfo nativeRuntime,
            Map<String, Long> packetAnomalies,
            List<PacketAnomalySample> recentPacketAnomalies,
            Map<String, ServerTraffic> serverTraffic,
            Map<String, ServerConnections> serverConnections,
            Map<String, PlayerSession> playerSessions,
            Map<PacketTrafficKey, PacketTraffic> packetTraffic,
            Map<CustomPayloadKey, CustomPayloadTraffic> customPayloads,
            List<CustomPayloadSample> recentCustomPayloads,
            Map<RelayBackpressureKey, RelayBackpressure> relayBackpressure,
            CompressionAudit compression,
            Map<String, CompressionAudit> serverCompression,
            Map<CompressionDirectionKey, CompressionAudit> serverCompressionByDirection,
            Map<String, Integer> serverCompressionThresholds,
            Map<CompressionDecisionKey, Long> compressionDecisions,
            Map<CompressionRewriteKey, CompressionRewrite> compressionRewrites,
            List<PayloadCapture> payloadCaptures,
            Map<String, List<PayloadCaptureSample>> payloadCaptureSamples) {
    }
}

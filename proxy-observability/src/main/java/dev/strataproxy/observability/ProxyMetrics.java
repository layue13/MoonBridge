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

public final class ProxyMetrics {
    private static final int RECENT_PACKET_ANOMALY_CAPACITY = 256;

    private final LongAdder acceptedConnections = new LongAdder();
    private final AtomicLong activeConnections = new AtomicLong();
    private final LongAdder rejectedConnections = new LongAdder();
    private final ConcurrentHashMap<String, LongAdder> rejectedConnectionsByReason = new ConcurrentHashMap<>();
    private final LongAdder handshakeTimeouts = new LongAdder();
    private final LongAdder routedConnections = new LongAdder();
    private final LongAdder failedRoutes = new LongAdder();
    private final LongAdder backendConnectFailures = new LongAdder();
    private final LongAdder frontendToBackendBytes = new LongAdder();
    private final LongAdder backendToFrontendBytes = new LongAdder();
    private final LongAdder compressionNegotiations = new LongAdder();
    private final AtomicLong packetAnomalySequence = new AtomicLong();
    private final AtomicReferenceArray<PacketAnomalySample> recentPacketAnomalies =
            new AtomicReferenceArray<>(RECENT_PACKET_ANOMALY_CAPACITY);
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

    public ProxyMetrics() {
        this(true);
    }

    public ProxyMetrics(boolean packetAnomalySampling) {
        this.packetAnomalySampling = packetAnomalySampling;
    }

    public void acceptedConnection() {
        acceptedConnections.increment();
        activeConnections.incrementAndGet();
    }

    public void closedConnection() {
        decrementGauge(activeConnections);
    }

    public void rejectedConnection() {
        rejectedConnection("unspecified");
    }

    public void rejectedConnection(String reason) {
        var normalized = reason == null || reason.isBlank() ? "unspecified" : sanitize(reason.trim());
        rejectedConnections.increment();
        rejectedConnectionsByReason.computeIfAbsent(normalized, ignored -> new LongAdder()).increment();
    }

    public void handshakeTimeout() {
        handshakeTimeouts.increment();
    }

    public void routedConnection() {
        routedConnections.increment();
    }

    public void serverConnectionOpened(String server) {
        serverConnections(server).connectionOpened();
    }

    public void serverConnectionClosed(String server) {
        serverConnections(server).connectionClosed();
    }

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

    public void playerSessionClosed(String player) {
        if (player != null && !player.isBlank()) {
            playerSessions.remove(player.trim());
        }
    }

    public void failedRoute() {
        failedRoutes.increment();
    }

    public void backendConnectFailure() {
        backendConnectFailures.increment();
    }

    public void frontendToBackendBytes(long bytes) {
        frontendToBackendBytes.add(bytes);
    }

    public void backendToFrontendBytes(long bytes) {
        backendToFrontendBytes.add(bytes);
    }

    public void frontendToBackendBytes(String server, long bytes) {
        frontendToBackendBytes(bytes);
        serverTraffic(server).frontendToBackendBytes.add(bytes);
    }

    public void backendToFrontendBytes(String server, long bytes) {
        backendToFrontendBytes(bytes);
        serverTraffic(server).backendToFrontendBytes.add(bytes);
    }

    public void packetAnomaly(String rule) {
        packetAnomaly(rule, "", "", "", "", -1, -1, -1, "");
    }

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

    public void customPayload(
            String server,
            CompressionDirection direction,
            String kind,
            String channel,
            long payloadBytes,
            long compressedBytes) {
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
        customPayloads.computeIfAbsent(
                new CustomPayloadKey(server, direction, normalizedKind, normalizedChannel(normalizedKind, channel)),
                ignored -> new CustomPayloadCounters()).add(payloadBytes, compressedBytes);
    }

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

    public boolean stopPayloadCapture(String id) {
        return id != null && payloadCaptures.remove(id) != null;
    }

    public List<PayloadCapture> payloadCaptures() {
        expirePayloadCaptures();
        return payloadCaptures.values().stream()
                .map(PayloadCaptureBuffer::capture)
                .sorted(Comparator.comparing(PayloadCapture::id))
                .toList();
    }

    public List<PayloadCaptureSample> payloadCaptureSamples(String id) {
        expirePayloadCaptures();
        var capture = payloadCaptures.get(id);
        return capture == null ? List.of() : capture.samples();
    }

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

    public void payloadCaptured(
            String server,
            CompressionDirection direction,
            long rawBytes,
            long compressedBytes,
            byte[] prefixBytes) {
        payloadCaptured(server, direction, rawBytes, compressedBytes, prefixBytes, "", "");
    }

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

    public void compressionSample(long rawBytes, long compressedBytes, long cpuNanos) {
        compression.add(rawBytes, compressedBytes, cpuNanos);
    }

    public void compressionSample(String server, long rawBytes, long compressedBytes, long cpuNanos) {
        compressionSample(rawBytes, compressedBytes, cpuNanos);
        serverCompression(server).add(rawBytes, compressedBytes, cpuNanos);
    }

    public void compressionSample(String server, CompressionDirection direction, long rawBytes, long compressedBytes, long cpuNanos) {
        compressionSample(server, rawBytes, compressedBytes, cpuNanos);
        serverCompression(server, direction).add(rawBytes, compressedBytes, cpuNanos);
    }

    public void compressionNegotiated(String server, int threshold) {
        if (threshold < 0) {
            throw new IllegalArgumentException("compression threshold must be non-negative");
        }
        compressionNegotiations.increment();
        serverCompressionThreshold(server).set(threshold);
    }

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

    public void compressionRewrite(String server, CompressionDirection direction, String outcome) {
        compressionRewrite(server, direction, outcome, 0);
    }

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

    public long currentEventLoopDelayNanos() {
        return eventLoopDelayNanos.get();
    }

    public void eventLoopDelayNanos(long nanos) {
        eventLoopDelayNanos.set(Math.max(0, nanos));
        maxEventLoopDelayNanos.accumulateAndGet(Math.max(0, nanos), Math::max);
    }

    public void pooledDirectMemoryBytes(long bytes) {
        pooledDirectMemoryBytes.set(Math.max(0, bytes));
    }

    public void networkTransport(String name, boolean nativeTransport) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("transport name must not be blank");
        }
        networkTransport.set(new NetworkTransport(name, nativeTransport));
    }

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

    public record ServerConnections(long routedConnections, long activeConnections) {
    }

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

    public record CustomPayloadTraffic(
            long packets,
            long payloadBytes,
            long compressedBytes,
            long maxPayloadBytes,
            long maxCompressedBytes,
            Instant firstSeen,
            Instant lastSeen) {
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

    public record PayloadCapture(
            String id,
            String server,
            CompressionDirection direction,
            int maxSamples,
            int maxBytesPerSample,
            Instant expiresAt) {
    }

    public record PayloadCaptureRequest(boolean enabled, int maxBytesPerSample) {
        private static PayloadCaptureRequest none() {
            return new PayloadCaptureRequest(false, 0);
        }
    }

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

    public record CompressionAudit(
            long samples,
            long rawBytes,
            long compressedBytes,
            long savedBytes,
            long cpuNanos) {
        public double ratio() {
            return rawBytes == 0 ? 1.0d : (double) compressedBytes / rawBytes;
        }
    }

    public record NetworkTransport(String name, boolean nativeTransport) {
    }

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

    public enum CompressionDirection {
        FRONTEND_TO_BACKEND("frontend_to_backend"),
        BACKEND_TO_FRONTEND("backend_to_frontend");

        private final String label;

        CompressionDirection(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record CompressionDirectionKey(String server, CompressionDirection direction) {
    }

    public record CompressionDecisionKey(String server, CompressionDirection direction, String action, int threshold) {
    }

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

    public record CompressionRewrite(long count, long cpuNanos) {
    }

    public record PacketTrafficKey(String server, CompressionDirection direction, String protocolState, int packetId) {
    }

    public record CustomPayloadKey(String server, CompressionDirection direction, String kind, String channel) {
    }

    public record RelayBackpressureKey(String server, CompressionDirection direction) {
    }

    public record Snapshot(
            long acceptedConnections,
            long activeConnections,
            long rejectedConnections,
            Map<String, Long> rejectedConnectionsByReason,
            long handshakeTimeouts,
            long routedConnections,
            long failedRoutes,
            long backendConnectFailures,
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

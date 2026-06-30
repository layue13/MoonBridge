package dev.strataproxy.admin;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import dev.strataproxy.api.server.DrainPolicy;
import dev.strataproxy.api.server.ProtocolRange;
import dev.strataproxy.api.server.RegisteredServer;
import dev.strataproxy.api.server.ServerCapability;
import dev.strataproxy.api.server.ServerDescriptor;
import dev.strataproxy.api.server.ServerHealth;
import dev.strataproxy.api.server.ServerHealthStatus;
import dev.strataproxy.api.server.ServerLoad;
import dev.strataproxy.api.server.ServerRegistry;
import dev.strataproxy.observability.ProxyMetrics;
import dev.strataproxy.routing.RoutingDecision;
import dev.strataproxy.routing.RoutingRequest;
import dev.strataproxy.routing.WeightedHealthAwareRouter;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;

public final class AdminHttpServer implements AutoCloseable {
    private static final String JSON = "application/json; charset=utf-8";

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final HttpServer server;
    private final AdminRegistryService registry;
    private final ProxyMetrics metrics;
    private final String bearerToken;
    private final int packetTopN;
    private final boolean prometheusEnabled;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();

    public AdminHttpServer(InetSocketAddress bindAddress, ServerRegistry registry, ProxyMetrics metrics) throws IOException {
        this(bindAddress, registry, metrics, "");
    }

    public AdminHttpServer(InetSocketAddress bindAddress, ServerRegistry registry, ProxyMetrics metrics, String bearerToken) throws IOException {
        this(bindAddress, new AdminRegistryService(registry, NoopRegistryStore.INSTANCE), metrics, bearerToken);
    }

    public AdminHttpServer(
            InetSocketAddress bindAddress,
            ServerRegistry registry,
            ProxyMetrics metrics,
            String bearerToken,
            int packetTopN) throws IOException {
        this(bindAddress, new AdminRegistryService(registry, NoopRegistryStore.INSTANCE), metrics, bearerToken, packetTopN);
    }

    public AdminHttpServer(
            InetSocketAddress bindAddress,
            ServerRegistry registry,
            ProxyMetrics metrics,
            String bearerToken,
            int packetTopN,
            boolean prometheusEnabled) throws IOException {
        this(bindAddress, new AdminRegistryService(registry, NoopRegistryStore.INSTANCE), metrics, bearerToken, packetTopN, prometheusEnabled);
    }

    public AdminHttpServer(InetSocketAddress bindAddress, AdminRegistryService registry, ProxyMetrics metrics, String bearerToken) throws IOException {
        this(bindAddress, registry, metrics, bearerToken, 50);
    }

    public AdminHttpServer(
            InetSocketAddress bindAddress,
            AdminRegistryService registry,
            ProxyMetrics metrics,
            String bearerToken,
            int packetTopN) throws IOException {
        this(bindAddress, registry, metrics, bearerToken, packetTopN, true);
    }

    public AdminHttpServer(
            InetSocketAddress bindAddress,
            AdminRegistryService registry,
            ProxyMetrics metrics,
            String bearerToken,
            int packetTopN,
            boolean prometheusEnabled) throws IOException {
        this(bindAddress, registry, metrics, bearerToken, packetTopN, prometheusEnabled, null, false);
    }

    public AdminHttpServer(
            InetSocketAddress bindAddress,
            AdminRegistryService registry,
            ProxyMetrics metrics,
            String bearerToken,
            int packetTopN,
            boolean prometheusEnabled,
            SSLContext sslContext,
            boolean requireClientAuth) throws IOException {
        this.registry = registry;
        this.metrics = metrics;
        this.bearerToken = bearerToken == null ? "" : bearerToken;
        this.packetTopN = Math.max(0, packetTopN);
        this.prometheusEnabled = prometheusEnabled;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.server = createServer(bindAddress, sslContext, requireClientAuth);
        this.server.createContext("/healthz", this::health);
        this.server.createContext("/readyz", this::ready);
        this.server.createContext("/overview", this::overview);
        this.server.createContext("/metrics", this::metrics);
        this.server.createContext("/native-capabilities", this::nativeCapabilities);
        this.server.createContext("/compression-report", this::compressionReport);
        this.server.createContext("/diagnostic-report", this::diagnosticReport);
        this.server.createContext("/packet-anomalies", this::packetAnomalies);
        this.server.createContext("/packet-traffic", this::packetTraffic);
        this.server.createContext("/custom-payloads", this::customPayloads);
        this.server.createContext("/player-sessions", this::playerSessions);
        this.server.createContext("/payload-captures", this::payloadCaptures);
        this.server.createContext("/routes", this::routes);
        this.server.createContext("/servers", this::servers);
        this.server.setExecutor(executor);
    }

    private static HttpServer createServer(
            InetSocketAddress bindAddress,
            SSLContext sslContext,
            boolean requireClientAuth) throws IOException {
        if (sslContext == null) {
            return HttpServer.create(bindAddress, 128);
        }
        var https = HttpsServer.create(bindAddress, 128);
        https.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
            @Override
            public void configure(HttpsParameters parameters) {
                var engine = getSSLContext().createSSLEngine();
                var sslParameters = getSSLContext().getDefaultSSLParameters();
                sslParameters.setNeedClientAuth(requireClientAuth);
                sslParameters.setCipherSuites(engine.getEnabledCipherSuites());
                sslParameters.setProtocols(engine.getEnabledProtocols());
                parameters.setSSLParameters(sslParameters);
            }
        });
        return https;
    }

    public void start() {
        server.start();
    }

    public InetSocketAddress bindAddress() {
        return server.getAddress();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        server.stop(1);
        executor.shutdownNow();
    }

    private void health(HttpExchange exchange) throws IOException {
        var body = "{\"status\":\"UP\",\"servers\":" + registry.snapshot().size() + ",\"timestamp\":\"" + Instant.now() + "\"}\n";
        respond(exchange, 200, JSON, body);
    }

    private void ready(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            respondError(exchange, 405, "method not allowed");
            return;
        }
        var servers = registry.snapshot();
        var readyServers = servers.stream().filter(AdminHttpServer::canReceiveNewConnections).count();
        var status = readyServers > 0 ? "READY" : "NOT_READY";
        respondJson(exchange, readyServers > 0 ? 200 : 503, new ReadinessView(
                status,
                readyServers,
                servers.size(),
                Instant.now().toString()));
    }

    private void overview(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        if (!exchange.getRequestMethod().equals("GET")) {
            respondError(exchange, 405, "method not allowed");
            return;
        }
        respondJson(exchange, 200, overviewView(metrics.snapshot(), registry.snapshot().size()));
    }

    private void metrics(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        if (!prometheusEnabled) {
            respondError(exchange, 404, "prometheus metrics disabled");
            return;
        }
        var snapshot = metrics.snapshot();
        var runtime = Runtime.getRuntime();
        var heapUsed = runtime.totalMemory() - runtime.freeMemory();
        var heapCommitted = runtime.totalMemory();
        var heapMax = runtime.maxMemory();
        var servers = registry.snapshot();
        var body = new StringBuilder(4096);
        appendMetric(body, "counter", "strataproxy_connections_accepted_total", snapshot.acceptedConnections());
        appendMetric(body, "gauge", "strataproxy_connections_active", snapshot.activeConnections());
        appendMetric(body, "counter", "strataproxy_connections_rejected_total", snapshot.rejectedConnections());
        for (var entry : snapshot.rejectedConnectionsByReason().entrySet()) {
            appendMetric(body, "counter", "strataproxy_connections_rejected_total", "reason=\"" + label(entry.getKey()) + "\"", entry.getValue());
        }
        appendMetric(body, "counter", "strataproxy_handshake_timeouts_total", snapshot.handshakeTimeouts());
        appendMetric(body, "counter", "strataproxy_connections_routed_total", snapshot.routedConnections());
        appendMetric(body, "counter", "strataproxy_routes_failed_total", snapshot.failedRoutes());
        appendMetric(body, "counter", "strataproxy_backend_connect_failures_total", snapshot.backendConnectFailures());
        appendMetric(body, "counter", "strataproxy_frontend_to_backend_bytes_total", snapshot.frontendToBackendBytes());
        appendMetric(body, "counter", "strataproxy_backend_to_frontend_bytes_total", snapshot.backendToFrontendBytes());
        appendMetric(body, "counter", "strataproxy_compression_negotiations_total", snapshot.compressionNegotiations());
        for (var entry : snapshot.packetAnomalies().entrySet()) {
            appendMetric(body, "counter", "strataproxy_packet_anomalies_total", "rule=\"" + label(entry.getKey()) + "\"", entry.getValue());
        }
        appendCompressionMetrics(body, "", snapshot.compression());
        appendMetric(body, "gauge", "strataproxy_servers_registered", servers.size());
        appendMetric(body, "gauge", "strataproxy_event_loop_delay_seconds", snapshot.eventLoopDelayNanos() / 1_000_000_000.0d);
        appendMetric(body, "gauge", "strataproxy_event_loop_delay_max_seconds", snapshot.maxEventLoopDelayNanos() / 1_000_000_000.0d);
        appendMetric(body, "gauge", "strataproxy_pooled_direct_memory_bytes", snapshot.pooledDirectMemoryBytes());
        appendMetric(body, "gauge", "strataproxy_network_transport_info", "transport=\""
                + label(snapshot.networkTransport().name()) + "\",native=\""
                + snapshot.networkTransport().nativeTransport() + "\"", 1);
        appendNativeRuntimeMetrics(body, snapshot.nativeRuntime());
        appendMetric(body, "gauge", "strataproxy_jvm_heap_used_bytes", heapUsed);
        appendMetric(body, "gauge", "strataproxy_jvm_heap_committed_bytes", heapCommitted);
        appendMetric(body, "gauge", "strataproxy_jvm_heap_max_bytes", heapMax);
        appendJvmMetrics(body);
        appendServerMetrics(body, servers, snapshot.serverTraffic(), snapshot.serverConnections(), snapshot.serverCompression(), snapshot.serverCompressionByDirection(), snapshot.serverCompressionThresholds());
        appendPlayerSessionMetrics(body, snapshot.playerSessions());
        appendCompressionDecisionMetrics(body, snapshot.compressionDecisions());
        appendCompressionRewriteMetrics(body, snapshot.compressionRewrites());
        appendPacketTrafficMetrics(body, snapshot.packetTraffic());
        appendCustomPayloadMetrics(body, snapshot.customPayloads());
        appendRelayBackpressureMetrics(body, snapshot.relayBackpressure());
        respond(exchange, 200, "text/plain; version=0.0.4; charset=utf-8", body.toString());
    }

    private void packetAnomalies(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        if (!exchange.getRequestMethod().equals("GET")) {
            respondError(exchange, 405, "method not allowed");
            return;
        }
        respondJson(exchange, 200, packetAnomalyReport(metrics.snapshot()));
    }

    private void packetTraffic(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        if (!exchange.getRequestMethod().equals("GET")) {
            respondError(exchange, 405, "method not allowed");
            return;
        }
        respondJson(exchange, 200, packetTrafficReport(metrics.snapshot()));
    }

    private void customPayloads(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        if (!exchange.getRequestMethod().equals("GET")) {
            respondError(exchange, 405, "method not allowed");
            return;
        }
        respondJson(exchange, 200, customPayloadReport(metrics.snapshot()));
    }

    private void playerSessions(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        if (!exchange.getRequestMethod().equals("GET")) {
            respondError(exchange, 405, "method not allowed");
            return;
        }
        respondJson(exchange, 200, playerSessionReport(metrics.snapshot()));
    }

    private void compressionReport(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        if (!exchange.getRequestMethod().equals("GET")) {
            respondError(exchange, 405, "method not allowed");
            return;
        }
        respondJson(exchange, 200, compressionReport(metrics.snapshot()));
    }

    private void nativeCapabilities(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        if (!exchange.getRequestMethod().equals("GET")) {
            respondError(exchange, 405, "method not allowed");
            return;
        }
        respondJson(exchange, 200, NativeRuntimeView.from(metrics.snapshot().nativeRuntime()));
    }

    private OverviewView overviewView(ProxyMetrics.Snapshot snapshot, long servers) {
        var totalAnomalies = snapshot.packetAnomalies().values().stream().mapToLong(Long::longValue).sum();
        return new OverviewView(
                "UP",
                servers,
                snapshot.activeConnections(),
                snapshot.routedConnections(),
                snapshot.rejectedConnections(),
                rejectionReasons(snapshot),
                snapshot.failedRoutes(),
                snapshot.backendConnectFailures(),
                snapshot.frontendToBackendBytes(),
                snapshot.backendToFrontendBytes(),
                snapshot.compressionNegotiations(),
                snapshot.compression().savedBytes(),
                snapshot.compression().ratio(),
                totalAnomalies,
                snapshot.eventLoopDelayNanos() / 1_000_000_000.0d,
                snapshot.pooledDirectMemoryBytes(),
                snapshot.networkTransport().name(),
                snapshot.networkTransport().nativeTransport());
    }

    private static void appendNativeRuntimeMetrics(StringBuilder body, ProxyMetrics.NativeRuntimeInfo runtime) {
        var labels = "enabled=\"" + runtime.enabled()
                + "\",os=\"" + label(runtime.os())
                + "\",arch=\"" + label(runtime.arch())
                + "\",source=\"" + label(runtime.detectionSource())
                + "\",tls_provider=\"" + label(runtime.tlsProvider())
                + "\",compression_provider=\"" + label(runtime.compressionProvider()) + "\"";
        appendMetric(body, "gauge", "strataproxy_native_runtime_info", labels, 1);
        appendMetric(body, "gauge", "strataproxy_native_prefer_transport", runtime.preferNativeTransport() ? 1 : 0);
        appendMetric(body, "gauge", "strataproxy_native_require_transport", runtime.requireNativeTransport() ? 1 : 0);
        for (var entry : runtime.features().entrySet()) {
            appendMetric(body, "gauge", "strataproxy_native_capability", "feature=\"" + label(entry.getKey()) + "\"", entry.getValue() ? 1 : 0);
        }
    }

    private RejectionReport rejectionReport(ProxyMetrics.Snapshot snapshot) {
        return new RejectionReport(snapshot.rejectedConnections(), rejectionReasons(snapshot));
    }

    private static Map<String, Long> rejectionReasons(ProxyMetrics.Snapshot snapshot) {
        return snapshot.rejectedConnectionsByReason().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        java.util.LinkedHashMap::new));
    }

    private PacketAnomalyReport packetAnomalyReport(ProxyMetrics.Snapshot snapshot) {
        var rules = snapshot.packetAnomalies().entrySet().stream()
                .map(entry -> new PacketAnomalyView(entry.getKey(), entry.getValue()))
                .sorted(java.util.Comparator
                        .comparingLong(PacketAnomalyView::count)
                        .reversed()
                        .thenComparing(PacketAnomalyView::rule))
                .limit(packetTopN)
                .toList();
        var total = snapshot.packetAnomalies().values().stream().mapToLong(Long::longValue).sum();
        var samples = snapshot.recentPacketAnomalies().stream()
                .map(PacketAnomalySampleView::from)
                .toList();
        return new PacketAnomalyReport(total, rules, samples);
    }

    private PacketTrafficReport packetTrafficReport(ProxyMetrics.Snapshot snapshot) {
        var rows = snapshot.packetTraffic().entrySet().stream()
                .map(entry -> PacketTrafficView.from(entry.getKey(), entry.getValue()))
                .sorted(java.util.Comparator
                        .comparingLong(PacketTrafficView::rawBytes)
                        .reversed()
                        .thenComparing(PacketTrafficView::server)
                        .thenComparing(PacketTrafficView::direction)
                        .thenComparing(PacketTrafficView::protocolState)
                        .thenComparingInt(PacketTrafficView::packetId))
                .limit(packetTopN)
                .toList();
        var totalPackets = snapshot.packetTraffic().values().stream().mapToLong(ProxyMetrics.PacketTraffic::packets).sum();
        var totalRawBytes = snapshot.packetTraffic().values().stream().mapToLong(ProxyMetrics.PacketTraffic::rawBytes).sum();
        var totalCompressedBytes = snapshot.packetTraffic().values().stream().mapToLong(ProxyMetrics.PacketTraffic::compressedBytes).sum();
        return new PacketTrafficReport(totalPackets, totalRawBytes, totalCompressedBytes, rows);
    }

    private CustomPayloadReport customPayloadReport(ProxyMetrics.Snapshot snapshot) {
        var rows = snapshot.customPayloads().entrySet().stream()
                .map(entry -> CustomPayloadView.from(entry.getKey(), entry.getValue()))
                .sorted(java.util.Comparator
                        .comparingLong(CustomPayloadView::payloadBytes)
                        .reversed()
                        .thenComparing(CustomPayloadView::server)
                        .thenComparing(CustomPayloadView::direction)
                        .thenComparing(CustomPayloadView::kind)
                        .thenComparing(CustomPayloadView::channel))
                .limit(packetTopN)
                .toList();
        var totalPackets = snapshot.customPayloads().values().stream().mapToLong(ProxyMetrics.CustomPayloadTraffic::packets).sum();
        var totalPayloadBytes = snapshot.customPayloads().values().stream().mapToLong(ProxyMetrics.CustomPayloadTraffic::payloadBytes).sum();
        var totalCompressedBytes = snapshot.customPayloads().values().stream().mapToLong(ProxyMetrics.CustomPayloadTraffic::compressedBytes).sum();
        return new CustomPayloadReport(totalPackets, totalPayloadBytes, totalCompressedBytes, rows);
    }

    private CompressionReport compressionReport(ProxyMetrics.Snapshot snapshot) {
        var rows = new java.util.ArrayList<CompressionReportRow>();
        rows.add(CompressionReportRow.from("global", "all", snapshot.compression(), -1, snapshot.compressionNegotiations()));
        for (var server : registry.snapshot()) {
            var name = server.descriptor().name();
            var serverCompression = snapshot.serverCompression().getOrDefault(
                    name,
                    new ProxyMetrics.CompressionAudit(0, 0, 0, 0, 0));
            rows.add(CompressionReportRow.from(
                    name,
                    "all",
                    serverCompression,
                    snapshot.serverCompressionThresholds().getOrDefault(name, -1),
                    0));
            for (var direction : ProxyMetrics.CompressionDirection.values()) {
                var directedCompression = snapshot.serverCompressionByDirection().getOrDefault(
                        new ProxyMetrics.CompressionDirectionKey(name, direction),
                        new ProxyMetrics.CompressionAudit(0, 0, 0, 0, 0));
                rows.add(CompressionReportRow.from(
                        name,
                        direction.label(),
                        directedCompression,
                        -1,
                        0));
            }
        }
        var decisions = snapshot.compressionDecisions().entrySet().stream()
                .map(entry -> CompressionDecisionView.from(entry.getKey(), entry.getValue()))
                .sorted(java.util.Comparator
                        .comparing(CompressionDecisionView::scope)
                        .thenComparing(CompressionDecisionView::direction)
                        .thenComparing(CompressionDecisionView::action)
                        .thenComparingLong(CompressionDecisionView::threshold))
                .toList();
        var rewrites = snapshot.compressionRewrites().entrySet().stream()
                .map(entry -> CompressionRewriteView.from(entry.getKey(), entry.getValue()))
                .sorted(java.util.Comparator
                        .comparing(CompressionRewriteView::scope)
                        .thenComparing(CompressionRewriteView::direction)
                        .thenComparing(CompressionRewriteView::outcome))
                .toList();
        return new CompressionReport(rows, decisions, rewrites);
    }

    private RelayBackpressureReport relayBackpressureReport(ProxyMetrics.Snapshot snapshot) {
        var rows = snapshot.relayBackpressure().entrySet().stream()
                .map(entry -> RelayBackpressureView.from(entry.getKey(), entry.getValue()))
                .sorted(java.util.Comparator
                        .comparingLong(RelayBackpressureView::events)
                        .reversed()
                        .thenComparing(RelayBackpressureView::server)
                        .thenComparing(RelayBackpressureView::direction))
                .limit(packetTopN)
                .toList();
        var totalEvents = snapshot.relayBackpressure().values().stream()
                .mapToLong(ProxyMetrics.RelayBackpressure::events)
                .sum();
        return new RelayBackpressureReport(totalEvents, rows);
    }

    private PayloadCaptureReport payloadCaptureReport(ProxyMetrics.Snapshot snapshot) {
        var rows = snapshot.payloadCaptures().stream()
                .map(capture -> PayloadCaptureView.from(
                        capture,
                        snapshot.payloadCaptureSamples().getOrDefault(capture.id(), List.of()).size()))
                .toList();
        return new PayloadCaptureReport(rows);
    }

    private void diagnosticReport(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        if (!exchange.getRequestMethod().equals("GET")) {
            respondError(exchange, 405, "method not allowed");
            return;
        }
        var snapshot = metrics.snapshot();
        var servers = registry.snapshot().stream()
                .map(ServerView::from)
                .sorted(java.util.Comparator.comparing(ServerView::name))
                .toList();
        respondJson(exchange, 200, new DiagnosticReport(
                Instant.now().toString(),
                overviewView(snapshot, servers.size()),
                rejectionReport(snapshot),
                servers,
                NativeRuntimeView.from(snapshot.nativeRuntime()),
                compressionReport(snapshot),
                packetTrafficReport(snapshot),
                customPayloadReport(snapshot),
                packetAnomalyReport(snapshot),
                relayBackpressureReport(snapshot),
                payloadCaptureReport(snapshot),
                playerSessionReport(snapshot)));
    }

    private static void appendCompressionMetrics(StringBuilder body, String labels, ProxyMetrics.CompressionAudit audit) {
        appendMetric(body, "counter", "strataproxy_compression_samples_total", labels, audit.samples());
        appendMetric(body, "counter", "strataproxy_compression_raw_bytes_total", labels, audit.rawBytes());
        appendMetric(body, "counter", "strataproxy_compression_compressed_bytes_total", labels, audit.compressedBytes());
        appendMetric(body, "counter", "strataproxy_compression_saved_bytes_total", labels, audit.savedBytes());
        appendMetric(body, "counter", "strataproxy_compression_cpu_seconds_total", labels, audit.cpuNanos() / 1_000_000_000.0d);
        appendMetric(body, "gauge", "strataproxy_compression_ratio", labels, audit.ratio());
    }

    private static void appendJvmMetrics(StringBuilder body) {
        var threads = ManagementFactory.getThreadMXBean();
        appendMetric(body, "gauge", "strataproxy_jvm_threads_live", threads.getThreadCount());
        appendMetric(body, "gauge", "strataproxy_jvm_threads_daemon", threads.getDaemonThreadCount());
        appendMetric(body, "gauge", "strataproxy_jvm_threads_peak", threads.getPeakThreadCount());
        for (var collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            var labels = "gc=\"" + label(collector.getName()) + "\"";
            appendMetric(body, "counter", "strataproxy_jvm_gc_collections_total", labels, Math.max(0, collector.getCollectionCount()));
            appendMetric(body, "counter", "strataproxy_jvm_gc_collection_seconds_total", labels, Math.max(0, collector.getCollectionTime()) / 1_000.0d);
        }
    }

    private static void appendServerMetrics(
            StringBuilder body,
            java.util.Collection<RegisteredServer> servers,
            Map<String, ProxyMetrics.ServerTraffic> serverTraffic,
            Map<String, ProxyMetrics.ServerConnections> serverConnections,
            Map<String, ProxyMetrics.CompressionAudit> serverCompression,
            Map<ProxyMetrics.CompressionDirectionKey, ProxyMetrics.CompressionAudit> serverCompressionByDirection,
            Map<String, Integer> serverCompressionThresholds) {
        for (var server : servers) {
            var descriptor = server.descriptor();
            var serverLabel = "server=\"" + label(descriptor.name()) + "\"";
            var traffic = serverTraffic.get(descriptor.name());
            var connections = serverConnections.getOrDefault(descriptor.name(), new ProxyMetrics.ServerConnections(0, 0));
            var compression = serverCompression.getOrDefault(descriptor.name(), new ProxyMetrics.CompressionAudit(0, 0, 0, 0, 0));
            appendMetric(body, "gauge", "strataproxy_server_draining", serverLabel, server.draining() ? 1 : 0);
            appendMetric(body, "gauge", "strataproxy_server_connections_active", serverLabel, connections.activeConnections());
            appendMetric(body, "counter", "strataproxy_server_connections_routed_total", serverLabel, connections.routedConnections());
            appendMetric(body, "gauge", "strataproxy_server_backend_ping_millis", serverLabel, server.health().backendPingMillis());
            appendMetric(body, "gauge", "strataproxy_server_recent_failure_ratio", serverLabel, server.health().recentFailureRate());
            appendMetric(body, "gauge", "strataproxy_server_players", serverLabel, server.load().players());
            appendMetric(body, "gauge", "strataproxy_server_soft_capacity", serverLabel, server.load().softCapacity());
            appendMetric(body, "gauge", "strataproxy_server_hard_capacity", serverLabel, server.load().hardCapacity());
            appendMetric(body, "gauge", "strataproxy_server_packets_per_second", serverLabel, server.load().packetsPerSecond());
            appendMetric(body, "gauge", "strataproxy_server_inbound_bytes_per_second", serverLabel, server.load().inboundBytesPerSecond());
            appendMetric(body, "gauge", "strataproxy_server_outbound_bytes_per_second", serverLabel, server.load().outboundBytesPerSecond());
            appendMetric(body, "counter", "strataproxy_server_frontend_to_backend_bytes_total", serverLabel, traffic == null ? 0 : traffic.frontendToBackendBytes());
            appendMetric(body, "counter", "strataproxy_server_backend_to_frontend_bytes_total", serverLabel, traffic == null ? 0 : traffic.backendToFrontendBytes());
            appendMetric(body, "gauge", "strataproxy_server_compression_threshold_bytes", serverLabel, serverCompressionThresholds.getOrDefault(descriptor.name(), -1));
            appendCompressionMetrics(body, serverLabel, compression);
            for (var direction : ProxyMetrics.CompressionDirection.values()) {
                var labels = serverLabel + ",direction=\"" + direction.label() + "\"";
                var directedCompression = serverCompressionByDirection.getOrDefault(
                        new ProxyMetrics.CompressionDirectionKey(descriptor.name(), direction),
                        new ProxyMetrics.CompressionAudit(0, 0, 0, 0, 0));
                appendCompressionMetrics(body, labels, directedCompression);
            }
            for (var status : ServerHealthStatus.values()) {
                var labels = serverLabel + ",status=\"" + status.name() + "\"";
                appendMetric(body, "gauge", "strataproxy_server_health", labels, server.health().status() == status ? 1 : 0);
            }
        }
    }

    private static void appendPlayerSessionMetrics(
            StringBuilder body,
            Map<String, ProxyMetrics.PlayerSession> players) {
        for (var session : players.values()) {
            var labels = "player=\"" + label(session.player())
                    + "\",server=\"" + label(session.server())
                    + "\",remote=\"" + label(session.remoteAddress()) + "\"";
            appendMetric(body, "gauge", "strataproxy_player_session_active", labels, 1);
        }
    }

    private static void appendCompressionDecisionMetrics(
            StringBuilder body,
            Map<ProxyMetrics.CompressionDecisionKey, Long> decisions) {
        for (var entry : decisions.entrySet()) {
            var key = entry.getKey();
            var labels = "server=\"" + label(key.server())
                    + "\",direction=\"" + key.direction().label()
                    + "\",action=\"" + label(key.action())
                    + "\",threshold=\"" + key.threshold() + "\"";
            appendMetric(body, "counter", "strataproxy_compression_decisions_total", labels, entry.getValue());
        }
    }

    private static void appendCompressionRewriteMetrics(
            StringBuilder body,
            Map<ProxyMetrics.CompressionRewriteKey, ProxyMetrics.CompressionRewrite> rewrites) {
        for (var entry : rewrites.entrySet()) {
            var key = entry.getKey();
            var value = entry.getValue();
            var labels = "server=\"" + label(key.server())
                    + "\",direction=\"" + key.direction().label()
                    + "\",outcome=\"" + label(key.outcome()) + "\"";
            appendMetric(body, "counter", "strataproxy_compression_rewrites_total", labels, value.count());
            appendMetric(body, "counter", "strataproxy_compression_rewrite_cpu_seconds_total", labels, value.cpuNanos() / 1_000_000_000.0d);
        }
    }

    private static void appendPacketTrafficMetrics(
            StringBuilder body,
            Map<ProxyMetrics.PacketTrafficKey, ProxyMetrics.PacketTraffic> traffic) {
        for (var entry : traffic.entrySet()) {
            var key = entry.getKey();
            var value = entry.getValue();
            var labels = "server=\"" + label(key.server())
                    + "\",direction=\"" + key.direction().label()
                    + "\",state=\"" + label(key.protocolState())
                    + "\",packet_id=\"" + key.packetId() + "\"";
            appendMetric(body, "counter", "strataproxy_packet_traffic_packets_total", labels, value.packets());
            appendMetric(body, "counter", "strataproxy_packet_traffic_raw_bytes_total", labels, value.rawBytes());
            appendMetric(body, "counter", "strataproxy_packet_traffic_compressed_bytes_total", labels, value.compressedBytes());
        }
    }

    private static void appendCustomPayloadMetrics(
            StringBuilder body,
            Map<ProxyMetrics.CustomPayloadKey, ProxyMetrics.CustomPayloadTraffic> payloads) {
        for (var entry : payloads.entrySet()) {
            var key = entry.getKey();
            var value = entry.getValue();
            var labels = "server=\"" + label(key.server())
                    + "\",direction=\"" + key.direction().label()
                    + "\",kind=\"" + label(key.kind())
                    + "\",channel=\"" + label(key.channel()) + "\"";
            appendMetric(body, "counter", "strataproxy_custom_payload_packets_total", labels, value.packets());
            appendMetric(body, "counter", "strataproxy_custom_payload_bytes_total", labels, value.payloadBytes());
            appendMetric(body, "counter", "strataproxy_custom_payload_compressed_bytes_total", labels, value.compressedBytes());
            appendMetric(body, "gauge", "strataproxy_custom_payload_max_bytes", labels, value.maxPayloadBytes());
            appendMetric(body, "gauge", "strataproxy_custom_payload_max_compressed_bytes", labels, value.maxCompressedBytes());
        }
    }

    private static void appendRelayBackpressureMetrics(
            StringBuilder body,
            Map<ProxyMetrics.RelayBackpressureKey, ProxyMetrics.RelayBackpressure> backpressure) {
        for (var entry : backpressure.entrySet()) {
            var key = entry.getKey();
            var value = entry.getValue();
            var labels = "server=\"" + label(key.server())
                    + "\",direction=\"" + key.direction().label() + "\"";
            appendMetric(body, "counter", "strataproxy_relay_backpressure_events_total", labels, value.events());
            appendMetric(body, "gauge", "strataproxy_relay_backpressure_last_bytes_before_writable", labels, value.lastBytesBeforeWritable());
            appendMetric(body, "gauge", "strataproxy_relay_backpressure_max_bytes_before_writable", labels, value.maxBytesBeforeWritable());
        }
    }

    private static void appendMetric(StringBuilder body, String type, String name, long value) {
        appendType(body, type, name);
        body.append(name).append(' ').append(value).append('\n');
    }

    private static void appendMetric(StringBuilder body, String type, String name, double value) {
        appendType(body, type, name);
        body.append(name).append(' ').append(value).append('\n');
    }

    private static void appendMetric(StringBuilder body, String type, String name, String labels, long value) {
        appendType(body, type, name);
        appendMetricLine(body, name, labels, value);
    }

    private static void appendMetric(StringBuilder body, String type, String name, String labels, double value) {
        appendType(body, type, name);
        appendMetricLine(body, name, labels, value);
    }

    private static void appendMetricLine(StringBuilder body, String name, String labels, long value) {
        if (labels == null || labels.isBlank()) {
            body.append(name).append(' ').append(value).append('\n');
        } else {
            body.append(name).append('{').append(labels).append("} ").append(value).append('\n');
        }
    }

    private static void appendMetricLine(StringBuilder body, String name, String labels, double value) {
        if (labels == null || labels.isBlank()) {
            body.append(name).append(' ').append(value).append('\n');
        } else {
            body.append(name).append('{').append(labels).append("} ").append(value).append('\n');
        }
    }

    private static void appendType(StringBuilder body, String type, String name) {
        body.append("# TYPE ").append(name).append(' ').append(type).append('\n');
    }

    private static String label(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private void payloadCaptures(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        try {
            var method = exchange.getRequestMethod();
            var segments = pathSegments(exchange);
            if (segments.size() == 1 && method.equals("GET")) {
                respondJson(exchange, 200, payloadCaptureReport(metrics.snapshot()));
                return;
            }
            if (segments.size() == 1 && method.equals("POST")) {
                var request = readRequest(exchange, PayloadCaptureRequest.class);
                var capture = metrics.startPayloadCapture(
                        request.id == null || request.id.isBlank() ? "capture-" + UUID.randomUUID() : request.id,
                        request.server,
                        parseDirection(request.direction),
                        request.maxSamples <= 0 ? 64 : request.maxSamples,
                        request.maxBytesPerSample <= 0 ? 256 : request.maxBytesPerSample,
                        Instant.now().plusMillis(request.durationMillis <= 0 ? 30_000 : request.durationMillis));
                respondJson(exchange, 201, PayloadCaptureView.from(capture, 0));
                return;
            }
            if (segments.size() == 2 && method.equals("GET")) {
                var id = segments.get(1);
                var capture = metrics.payloadCaptures().stream()
                        .filter(item -> item.id().equals(id))
                        .findFirst();
                if (capture.isEmpty()) {
                    respondError(exchange, 404, "payload capture not found");
                    return;
                }
                respondJson(exchange, 200, PayloadCaptureExport.from(capture.get(), metrics.payloadCaptureSamples(id)));
                return;
            }
            if (segments.size() == 2 && method.equals("DELETE")) {
                var removed = metrics.stopPayloadCapture(segments.get(1));
                respondJson(exchange, removed ? 200 : 404, Map.of("removed", removed));
                return;
            }
            respondError(exchange, 404, "unsupported payload capture route");
        } catch (IllegalArgumentException exception) {
            respondError(exchange, 400, exception.getMessage());
        }
    }

    private void routes(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        try {
            var method = exchange.getRequestMethod();
            var segments = pathSegments(exchange);
            if (segments.size() == 2 && segments.get(1).equals("preview") && method.equals("GET")) {
                respondJson(exchange, 200, routePreview(exchange));
                return;
            }
            respondError(exchange, 404, "unsupported route operation");
        } catch (IllegalArgumentException exception) {
            respondError(exchange, 400, exception.getMessage());
        }
    }

    private void servers(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        try {
            var method = exchange.getRequestMethod();
            var segments = pathSegments(exchange);
            if (segments.size() == 1 && method.equals("GET")) {
                respondJson(exchange, 200, registry.snapshot().stream().map(ServerView::from).toList());
                return;
            }
            if (segments.size() == 1 && method.equals("POST")) {
                var request = readRequest(exchange, ServerRequest.class);
                var descriptor = request.toDescriptor();
                var replacing = registry.get(descriptor.name()).isPresent();
                var server = registry.register(descriptor);
                respondJson(exchange, replacing ? 200 : 201, ServerView.from(server));
                return;
            }
            if (segments.size() == 2 && method.equals("GET")) {
                var server = registry.get(segments.get(1));
                if (server.isEmpty()) {
                    respondError(exchange, 404, "server not found");
                    return;
                }
                respondJson(exchange, 200, ServerView.from(server.get()));
                return;
            }
            if (segments.size() == 2 && method.equals("PATCH")) {
                var server = registry.get(segments.get(1));
                if (server.isEmpty()) {
                    respondError(exchange, 404, "server not found");
                    return;
                }
                var request = readRequest(exchange, ServerPatchRequest.class);
                var updated = registry.register(request.patch(server.get().descriptor()));
                respondJson(exchange, 200, ServerView.from(updated));
                return;
            }
            if (segments.size() == 2 && method.equals("DELETE")) {
                var removed = registry.unregister(segments.get(1), DrainPolicy.rejectNew());
                respondJson(exchange, removed ? 200 : 404, Map.of("removed", removed));
                return;
            }
            if (segments.size() == 3 && method.equals("POST") && segments.get(2).equals("drain")) {
                var drained = registry.updateDrainMode(segments.get(1), true);
                respondJson(exchange, drained ? 200 : 404, Map.of("draining", drained));
                return;
            }
            if (segments.size() == 3 && method.equals("POST") && segments.get(2).equals("undrain")) {
                var undrained = registry.updateDrainMode(segments.get(1), false);
                respondJson(exchange, undrained ? 200 : 404, Map.of("draining", false));
                return;
            }
            if (segments.size() == 3 && method.equals("POST") && segments.get(2).equals("health")) {
                if (registry.get(segments.get(1)).isEmpty()) {
                    respondError(exchange, 404, "server not found");
                    return;
                }
                var request = readRequest(exchange, HealthRequest.class);
                registry.updateHealth(segments.get(1), request.toHealth());
                respondJson(exchange, 200, registry.get(segments.get(1)).map(ServerView::from).orElseThrow());
                return;
            }
            if (segments.size() == 3 && method.equals("POST") && segments.get(2).equals("load")) {
                if (registry.get(segments.get(1)).isEmpty()) {
                    respondError(exchange, 404, "server not found");
                    return;
                }
                var request = readRequest(exchange, LoadRequest.class);
                registry.updateLoad(segments.get(1), request.toLoad());
                respondJson(exchange, 200, registry.get(segments.get(1)).map(ServerView::from).orElseThrow());
                return;
            }
            respondError(exchange, 404, "unsupported admin route");
        } catch (IllegalArgumentException exception) {
            respondError(exchange, 400, exception.getMessage());
        } catch (IOException exception) {
            respondError(exchange, 500, exception.getMessage());
        }
    }

    private <T> T readRequest(HttpExchange exchange, Class<T> type) {
        try {
            return mapper.readValue(exchange.getRequestBody(), type);
        } catch (JsonMappingException exception) {
            throw new IllegalArgumentException("invalid json request: " + exception.getOriginalMessage(), exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("invalid json request: " + exception.getMessage(), exception);
        }
    }

    private void respondJson(HttpExchange exchange, int status, Object value) throws IOException {
        respond(exchange, status, JSON, mapper.writeValueAsString(value) + "\n");
    }

    private void respondError(HttpExchange exchange, int status, String message) throws IOException {
        respondJson(exchange, status, Map.of("error", message == null ? "" : message));
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private boolean authorized(HttpExchange exchange) throws IOException {
        if (bearerToken.isBlank()) {
            return true;
        }
        var expected = "Bearer " + bearerToken;
        var actual = exchange.getRequestHeaders().getFirst("Authorization");
        if (actual != null && constantTimeEquals(actual, expected)) {
            return true;
        }
        exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
        respondError(exchange, 401, "unauthorized");
        return false;
    }

    private static boolean constantTimeEquals(String actual, String expected) {
        return MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> pathSegments(HttpExchange exchange) {
        return java.util.Arrays.stream(exchange.getRequestURI().getPath().split("/"))
                .filter(segment -> !segment.isBlank())
                .toList();
    }

    private RoutePreviewView routePreview(HttpExchange exchange) {
        var query = queryParams(exchange);
        var protocolVersionText = first(query, "protocolVersion");
        if (protocolVersionText == null || protocolVersionText.isBlank()) {
            throw new IllegalArgumentException("protocolVersion query parameter is required");
        }
        var protocolVersion = Integer.parseInt(protocolVersionText);
        var route = first(query, "route");
        var remoteAddress = parseAddress(firstOrDefault(query, "remoteAddress", "127.0.0.1:0"), 0);
        var tags = queryValues(query, "tag");
        var capabilities = queryValues(query, "capability").stream()
                .map(ServerRequest::parseCapability)
                .collect(Collectors.toUnmodifiableSet());
        var request = new RoutingRequest(route, Set.copyOf(tags), capabilities, protocolVersion, remoteAddress);
        var router = new WeightedHealthAwareRouter(readOnlyRegistry());
        var decision = router.route(request);
        var candidates = router.explain(request).stream()
                .map(candidate -> new RouteCandidateView(
                        candidate.serverName(),
                        candidate.eligible(),
                        candidate.reason(),
                        candidate.effectiveWeight(),
                        candidate.selectionKey()))
                .toList();
        if (decision instanceof RoutingDecision.Selected selected) {
            return new RoutePreviewView(
                    true,
                    selected.server().descriptor().name(),
                    selected.score(),
                    "",
                    route == null ? "" : route,
                    protocolVersion,
                    remoteAddress.toString(),
                    candidates);
        }
        var rejected = (RoutingDecision.Rejected) decision;
        return new RoutePreviewView(
                false,
                "",
                0.0d,
                rejected.reason(),
                route == null ? "" : route,
                protocolVersion,
                remoteAddress.toString(),
                candidates);
    }

    private ServerRegistry readOnlyRegistry() {
        return new ServerRegistry() {
            @Override
            public RegisteredServer register(ServerDescriptor descriptor) {
                throw new UnsupportedOperationException("read-only registry");
            }

            @Override
            public RegisteredServer registerOrReplace(ServerDescriptor descriptor) {
                throw new UnsupportedOperationException("read-only registry");
            }

            @Override
            public boolean unregister(String name, DrainPolicy policy) {
                throw new UnsupportedOperationException("read-only registry");
            }

            @Override
            public java.util.Optional<RegisteredServer> get(String name) {
                return registry.get(name);
            }

            @Override
            public java.util.Collection<RegisteredServer> snapshot() {
                return registry.snapshot();
            }

            @Override
            public void updateHealth(String name, ServerHealth health) {
                throw new UnsupportedOperationException("read-only registry");
            }

            @Override
            public void updateLoad(String name, ServerLoad load) {
                throw new UnsupportedOperationException("read-only registry");
            }

            @Override
            public void updateDrainMode(String name, boolean drainMode) {
                throw new UnsupportedOperationException("read-only registry");
            }
        };
    }

    private static Map<String, List<String>> queryParams(HttpExchange exchange) {
        var query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) {
            return Map.of();
        }
        var result = new java.util.LinkedHashMap<String, List<String>>();
        for (var pair : query.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            var splitAt = pair.indexOf('=');
            var key = decode(splitAt < 0 ? pair : pair.substring(0, splitAt));
            var value = decode(splitAt < 0 ? "" : pair.substring(splitAt + 1));
            result.compute(key, (ignored, values) -> {
                var next = values == null ? new java.util.ArrayList<String>() : new java.util.ArrayList<>(values);
                next.add(value);
                return List.copyOf(next);
            });
        }
        return Map.copyOf(result);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String first(Map<String, List<String>> query, String name) {
        var values = query.get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    private static String firstOrDefault(Map<String, List<String>> query, String name, String fallback) {
        var value = first(query, name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Set<String> queryValues(Map<String, List<String>> query, String name) {
        var values = query.get(name);
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        return values.stream()
                .flatMap(value -> java.util.Arrays.stream(value.split(",")))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    public static final class ServerRequest {
        public String name;
        public String address;
        public Set<String> tags = Set.of();
        public Set<String> capabilities = Set.of();
        public String protocolRange = "any";
        public int weight = 100;
        public int softCapacity = 500;
        public int hardCapacity = 600;
        public boolean drainMode = false;
        public Map<String, String> metadata = Map.of();

        ServerDescriptor toDescriptor() {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("server name must not be blank");
            }
            if (address == null || address.isBlank()) {
                throw new IllegalArgumentException("server address must not be blank");
            }
            if (weight <= 0) {
                throw new IllegalArgumentException("server weight must be positive");
            }
            if (softCapacity < 0 || hardCapacity < 0) {
                throw new IllegalArgumentException("server capacity values must be non-negative");
            }
            if (hardCapacity > 0 && softCapacity > hardCapacity) {
                throw new IllegalArgumentException("server softCapacity must be <= hardCapacity");
            }
            return new ServerDescriptor(
                    name,
                    parseAddress(address, 25565),
                    tags,
                    capabilities.stream().map(ServerRequest::parseCapability).collect(Collectors.toUnmodifiableSet()),
                    parseProtocolRange(protocolRange),
                    weight,
                    softCapacity,
                    hardCapacity,
                    drainMode,
                    metadata);
        }

        private static ServerCapability parseCapability(String value) {
            return ServerCapability.valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
        }
    }

    public static final class HealthRequest {
        public String status = "UP";
        public long backendPingMillis = -1;
        public double recentFailureRate = 0.0d;
        public String reason = "";

        ServerHealth toHealth() {
            if (status == null || status.isBlank()) {
                throw new IllegalArgumentException("health status must not be blank");
            }
            return new ServerHealth(
                    ServerHealthStatus.valueOf(status.trim().replace('-', '_').toUpperCase(Locale.ROOT)),
                    backendPingMillis,
                    recentFailureRate,
                    reason,
                    Instant.now());
        }
    }

    public static final class ServerPatchRequest {
        public String address;
        public Set<String> tags;
        public Set<String> capabilities;
        public String protocolRange;
        public Integer weight;
        public Integer softCapacity;
        public Integer hardCapacity;
        public Boolean drainMode;
        public Map<String, String> metadata;

        ServerDescriptor patch(ServerDescriptor current) {
            var nextWeight = weight == null ? current.weight() : weight;
            var nextSoftCapacity = softCapacity == null ? current.softCapacity() : softCapacity;
            var nextHardCapacity = hardCapacity == null ? current.hardCapacity() : hardCapacity;
            if (nextWeight <= 0) {
                throw new IllegalArgumentException("server weight must be positive");
            }
            if (nextSoftCapacity < 0 || nextHardCapacity < 0) {
                throw new IllegalArgumentException("server capacity values must be non-negative");
            }
            if (nextHardCapacity > 0 && nextSoftCapacity > nextHardCapacity) {
                throw new IllegalArgumentException("server softCapacity must be <= hardCapacity");
            }
            return new ServerDescriptor(
                    current.name(),
                    address == null ? current.address() : parseAddress(address, current.address().getPort()),
                    tags == null ? current.tags() : tags,
                    capabilities == null
                            ? current.capabilities()
                            : capabilities.stream()
                                    .map(ServerRequest::parseCapability)
                                    .collect(Collectors.toUnmodifiableSet()),
                    protocolRange == null ? current.protocolRange() : parseProtocolRange(protocolRange),
                    nextWeight,
                    nextSoftCapacity,
                    nextHardCapacity,
                    drainMode == null ? current.drainMode() : drainMode,
                    metadata == null ? current.metadata() : metadata);
        }
    }

    public static final class LoadRequest {
        public int players;
        public int softCapacity;
        public int hardCapacity;
        public long inboundBytesPerSecond;
        public long outboundBytesPerSecond;
        public long packetsPerSecond;
        public double eventLoopDelayMillis;

        ServerLoad toLoad() {
            if (players < 0 || softCapacity < 0 || hardCapacity < 0) {
                throw new IllegalArgumentException("load values must be non-negative");
            }
            if (inboundBytesPerSecond < 0 || outboundBytesPerSecond < 0 || packetsPerSecond < 0) {
                throw new IllegalArgumentException("load traffic values must be non-negative");
            }
            if (eventLoopDelayMillis < 0.0d) {
                throw new IllegalArgumentException("eventLoopDelayMillis must be non-negative");
            }
            return new ServerLoad(
                    players,
                    softCapacity,
                    hardCapacity,
                    inboundBytesPerSecond,
                    outboundBytesPerSecond,
                    packetsPerSecond,
                    eventLoopDelayMillis);
        }
    }

    public record ServerView(
            String name,
            String address,
            Set<String> tags,
            Set<ServerCapability> capabilities,
            ProtocolRange protocolRange,
            int weight,
            boolean draining,
            HealthView health,
            ServerLoad load,
            Map<String, String> metadata) {
        static ServerView from(RegisteredServer server) {
            var descriptor = server.descriptor();
            return new ServerView(
                    descriptor.name(),
                    descriptor.address().getHostString() + ":" + descriptor.address().getPort(),
                    descriptor.tags(),
                    descriptor.capabilities(),
                    descriptor.protocolRange(),
                    descriptor.weight(),
                    server.draining(),
                    HealthView.from(server.health()),
                    server.load(),
                    descriptor.metadata());
        }
    }

    public record HealthView(
            ServerHealthStatus status,
            long backendPingMillis,
            double recentFailureRate,
            String reason,
            String updatedAt) {
        static HealthView from(ServerHealth health) {
            return new HealthView(
                    health.status(),
                    health.backendPingMillis(),
                    health.recentFailureRate(),
                    health.reason(),
                    health.updatedAt().toString());
        }
    }

    public record OverviewView(
            String status,
            long servers,
            long activeConnections,
            long routedConnections,
            long rejectedConnections,
            Map<String, Long> rejectedConnectionsByReason,
            long failedRoutes,
            long backendConnectFailures,
            long frontendToBackendBytes,
            long backendToFrontendBytes,
            long compressionNegotiations,
            long compressionSavedBytes,
            double compressionRatio,
            long packetAnomalies,
            double eventLoopDelaySeconds,
            long pooledDirectMemoryBytes,
            String transport,
            boolean nativeTransport) {
    }

    public record NativeRuntimeView(
            boolean enabled,
            String os,
            String arch,
            String detectionSource,
            String tlsProvider,
            String compressionProvider,
            boolean preferNativeTransport,
            boolean requireNativeTransport,
            Map<String, Boolean> features) {
        static NativeRuntimeView from(ProxyMetrics.NativeRuntimeInfo runtime) {
            return new NativeRuntimeView(
                    runtime.enabled(),
                    runtime.os(),
                    runtime.arch(),
                    runtime.detectionSource(),
                    runtime.tlsProvider(),
                    runtime.compressionProvider(),
                    runtime.preferNativeTransport(),
                    runtime.requireNativeTransport(),
                    runtime.features());
        }
    }

    private static boolean canReceiveNewConnections(RegisteredServer server) {
        return !server.draining()
                && server.health().canReceiveNewConnections()
                && !server.load().isHardFull();
    }

    public record ReadinessView(String status, long readyServers, long registeredServers, String timestamp) {
    }

    public record RoutePreviewView(
            boolean selected,
            String server,
            double score,
            String reason,
            String route,
            int protocolVersion,
            String remoteAddress,
            List<RouteCandidateView> candidates) {
    }

    public record RouteCandidateView(
            String server,
            boolean eligible,
            String reason,
            double effectiveWeight,
            double selectionKey) {
    }

    public record DiagnosticReport(
            String generatedAt,
            OverviewView overview,
            RejectionReport admission,
            List<ServerView> servers,
            NativeRuntimeView nativeRuntime,
            CompressionReport compression,
            PacketTrafficReport packetTraffic,
            CustomPayloadReport customPayloads,
            PacketAnomalyReport packetAnomalies,
            RelayBackpressureReport relayBackpressure,
            PayloadCaptureReport payloadCaptures,
            PlayerSessionReport playerSessions) {
    }

    public record RejectionReport(long rejectedConnections, Map<String, Long> rejectedConnectionsByReason) {
    }

    private PlayerSessionReport playerSessionReport(ProxyMetrics.Snapshot snapshot) {
        var players = snapshot.playerSessions().values().stream()
                .map(PlayerSessionView::from)
                .sorted(java.util.Comparator
                        .comparing(PlayerSessionView::server)
                        .thenComparing(PlayerSessionView::player))
                .toList();
        return new PlayerSessionReport(players.size(), players);
    }

    public record PlayerSessionReport(int active, List<PlayerSessionView> players) {
    }

    public record PlayerSessionView(String player, String server, String remoteAddress, String connectedAt) {
        static PlayerSessionView from(ProxyMetrics.PlayerSession session) {
            return new PlayerSessionView(
                    session.player(),
                    session.server(),
                    session.remoteAddress(),
                    session.connectedAt().toString());
        }
    }

    public record PacketAnomalyReport(long total, List<PacketAnomalyView> rules, List<PacketAnomalySampleView> recentSamples) {
    }

    public record PacketAnomalyView(String rule, long count) {
    }

    public record PacketAnomalySampleView(
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
            String timestamp) {
        static PacketAnomalySampleView from(ProxyMetrics.PacketAnomalySample sample) {
            return new PacketAnomalySampleView(
                    sample.sequence(),
                    sample.rule(),
                    sample.remoteAddress(),
                    sample.server(),
                    sample.direction(),
                    sample.protocolState(),
                    sample.packetId(),
                    sample.rawSize(),
                    sample.compressedSize(),
                    sample.detail(),
                    sample.timestamp().toString());
        }
    }

    public record PacketTrafficReport(
            long totalPackets,
            long totalRawBytes,
            long totalCompressedBytes,
            List<PacketTrafficView> top) {
    }

    public record PacketTrafficView(
            String server,
            String direction,
            String protocolState,
            int packetId,
            long packets,
            long rawBytes,
            long compressedBytes) {
        static PacketTrafficView from(ProxyMetrics.PacketTrafficKey key, ProxyMetrics.PacketTraffic traffic) {
            return new PacketTrafficView(
                    key.server(),
                    key.direction().label(),
                    key.protocolState(),
                    key.packetId(),
                    traffic.packets(),
                    traffic.rawBytes(),
                    traffic.compressedBytes());
        }
    }

    public record CustomPayloadReport(
            long totalPackets,
            long totalPayloadBytes,
            long totalCompressedBytes,
            List<CustomPayloadView> top) {
    }

    public record CustomPayloadView(
            String server,
            String direction,
            String kind,
            String channel,
            long packets,
            long payloadBytes,
            long compressedBytes,
            long maxPayloadBytes,
            long maxCompressedBytes,
            String firstSeen,
            String lastSeen) {
        static CustomPayloadView from(ProxyMetrics.CustomPayloadKey key, ProxyMetrics.CustomPayloadTraffic traffic) {
            return new CustomPayloadView(
                    key.server(),
                    key.direction().label(),
                    key.kind(),
                    key.channel(),
                    traffic.packets(),
                    traffic.payloadBytes(),
                    traffic.compressedBytes(),
                    traffic.maxPayloadBytes(),
                    traffic.maxCompressedBytes(),
                    traffic.firstSeen() == null ? "" : traffic.firstSeen().toString(),
                    traffic.lastSeen() == null ? "" : traffic.lastSeen().toString());
        }
    }

    public record RelayBackpressureReport(long totalEvents, List<RelayBackpressureView> top) {
    }

    public record RelayBackpressureView(
            String server,
            String direction,
            long events,
            long lastBytesBeforeWritable,
            long maxBytesBeforeWritable) {
        static RelayBackpressureView from(ProxyMetrics.RelayBackpressureKey key, ProxyMetrics.RelayBackpressure value) {
            return new RelayBackpressureView(
                    key.server(),
                    key.direction().label(),
                    value.events(),
                    value.lastBytesBeforeWritable(),
                    value.maxBytesBeforeWritable());
        }
    }

    public static final class PayloadCaptureRequest {
        public String id = "";
        public String server = "";
        public String direction = "";
        public int maxSamples = 64;
        public int maxBytesPerSample = 256;
        public long durationMillis = 30_000;
    }

    public record PayloadCaptureReport(List<PayloadCaptureView> captures) {
    }

    public record PayloadCaptureExport(PayloadCaptureView capture, List<PayloadCaptureSampleView> samples) {
        static PayloadCaptureExport from(ProxyMetrics.PayloadCapture capture, List<ProxyMetrics.PayloadCaptureSample> samples) {
            return new PayloadCaptureExport(
                    PayloadCaptureView.from(capture, samples.size()),
                    samples.stream().map(PayloadCaptureSampleView::from).toList());
        }
    }

    public record PayloadCaptureView(
            String id,
            String server,
            String direction,
            int maxSamples,
            int maxBytesPerSample,
            String expiresAt,
            int sampleCount) {
        static PayloadCaptureView from(ProxyMetrics.PayloadCapture capture, int sampleCount) {
            return new PayloadCaptureView(
                    capture.id(),
                    capture.server(),
                    capture.direction().label(),
                    capture.maxSamples(),
                    capture.maxBytesPerSample(),
                    capture.expiresAt().toString(),
                    sampleCount);
        }
    }

    public record PayloadCaptureSampleView(
            long sequence,
            String captureId,
            String server,
            String direction,
            long rawBytes,
            long compressedBytes,
            String prefixBase64,
            String player,
            String remoteAddress,
            String timestamp) {
        static PayloadCaptureSampleView from(ProxyMetrics.PayloadCaptureSample sample) {
            return new PayloadCaptureSampleView(
                    sample.sequence(),
                    sample.captureId(),
                    sample.server(),
                    sample.direction().label(),
                    sample.rawBytes(),
                    sample.compressedBytes(),
                    Base64.getEncoder().encodeToString(sample.prefixBytes()),
                    sample.player(),
                    sample.remoteAddress(),
                    sample.timestamp().toString());
        }
    }

    public record CompressionReport(
            List<CompressionReportRow> rows,
            List<CompressionDecisionView> decisions,
            List<CompressionRewriteView> rewrites) {
    }

    public record CompressionReportRow(
            String scope,
            String direction,
            long rawBytes,
            long compressedBytes,
            long savedBytes,
            double ratio,
            long samples,
            int threshold,
            long negotiations) {
        static CompressionReportRow from(
                String scope,
                String direction,
                ProxyMetrics.CompressionAudit audit,
                int threshold,
                long negotiations) {
            return new CompressionReportRow(
                    scope,
                    direction,
                    audit.rawBytes(),
                    audit.compressedBytes(),
                    audit.savedBytes(),
                    audit.ratio(),
                    audit.samples(),
                    threshold,
                    negotiations);
        }
    }

    public record CompressionDecisionView(
            String scope,
            String direction,
            String action,
            int threshold,
            long count) {
        static CompressionDecisionView from(ProxyMetrics.CompressionDecisionKey key, long count) {
            return new CompressionDecisionView(
                    key.server(),
                    key.direction().label(),
                    key.action(),
                    key.threshold(),
                    count);
        }
    }

    public record CompressionRewriteView(
            String scope,
            String direction,
            String outcome,
            long count,
            long cpuNanos) {
        static CompressionRewriteView from(ProxyMetrics.CompressionRewriteKey key, ProxyMetrics.CompressionRewrite rewrite) {
            return new CompressionRewriteView(
                    key.server(),
                    key.direction().label(),
                    key.outcome(),
                    rewrite.count(),
                    rewrite.cpuNanos());
        }
    }

    private static InetSocketAddress parseAddress(String value, int defaultPort) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("address must not be blank");
        }
        var trimmed = value.trim();
        var splitAt = trimmed.lastIndexOf(':');
        if (splitAt <= 0) {
            return new InetSocketAddress(trimmed, defaultPort);
        }
        return new InetSocketAddress(trimmed.substring(0, splitAt), Integer.parseInt(trimmed.substring(splitAt + 1)));
    }

    private static ProtocolRange parseProtocolRange(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("any")) {
            return new ProtocolRange(0, Integer.MAX_VALUE, "any");
        }
        var trimmed = value.trim();
        if (trimmed.matches("\\d+")) {
            var protocol = Integer.parseInt(trimmed);
            return new ProtocolRange(protocol, protocol, trimmed);
        }
        var bounds = trimmed.split("\\.\\.", 2);
        if (bounds.length == 2) {
            return new ProtocolRange(Integer.parseInt(bounds[0]), Integer.parseInt(bounds[1]), trimmed);
        }
        throw new IllegalArgumentException("unsupported protocolRange: " + value);
    }

    private static ProxyMetrics.CompressionDirection parseDirection(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("direction must not be blank");
        }
        var normalized = value.trim().toLowerCase(Locale.ROOT);
        for (var direction : ProxyMetrics.CompressionDirection.values()) {
            if (direction.label().equals(normalized) || direction.name().equalsIgnoreCase(value)) {
                return direction;
            }
        }
        throw new IllegalArgumentException("direction must be frontend_to_backend or backend_to_frontend");
    }
}

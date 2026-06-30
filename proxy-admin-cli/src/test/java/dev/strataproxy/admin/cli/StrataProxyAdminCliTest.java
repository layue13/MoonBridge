package dev.strataproxy.admin.cli;

import dev.strataproxy.admin.AdminHttpServer;
import dev.strataproxy.admin.AdminRegistryService;
import dev.strataproxy.admin.NoopRegistryStore;
import dev.strataproxy.api.server.ProtocolRange;
import dev.strataproxy.api.server.ServerDescriptor;
import dev.strataproxy.observability.ProxyMetrics;
import dev.strataproxy.registry.InMemoryServerRegistry;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StrataProxyAdminCliTest {
    @Test
    void fetchesHealthAndManagesServers() throws Exception {
        var registry = new InMemoryServerRegistry();
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, new ProxyMetrics())) {
            admin.start();
            var baseUrl = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();

            var health = execute("--base-url", baseUrl, "health");
            assertEquals(0, health.exitCode());
            assertTrue(health.output().contains("\"status\":\"UP\""));

            var notReady = execute("--base-url", baseUrl, "ready");
            assertEquals(1, notReady.exitCode());
            assertTrue(notReady.output().contains("\"status\":\"NOT_READY\""));

            var register = execute(
                    "--base-url", baseUrl,
                    "servers", "register",
                    "--name", "survival-1",
                    "--address", "127.0.0.1:25565",
                    "--tag", "survival,forge",
                    "--capability", "large-payload",
                    "--protocol-range", "763",
                    "--metadata", "host=survival.local,group=survival");
            assertEquals(0, register.exitCode());
            assertTrue(register.output().contains("\"name\":\"survival-1\""));

            var ready = execute("--base-url", baseUrl, "ready");
            assertEquals(0, ready.exitCode());
            assertTrue(ready.output().contains("\"status\":\"READY\""));

            var list = execute("--base-url", baseUrl, "servers", "list");
            assertEquals(0, list.exitCode());
            assertTrue(list.output().contains("survival-1"));

            var drain = execute("--base-url", baseUrl, "servers", "drain", "survival-1");
            assertEquals(0, drain.exitCode());
            assertTrue(drain.output().contains("\"draining\":true"));

            var undrain = execute("--base-url", baseUrl, "servers", "undrain", "survival-1");
            assertEquals(0, undrain.exitCode());
            assertTrue(undrain.output().contains("\"draining\":false"));
        }
    }

    @Test
    void updatesServerDescriptorPartially() throws Exception {
        var registry = new InMemoryServerRegistry();
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, new ProxyMetrics())) {
            admin.start();
            var baseUrl = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();

            var register = execute(
                    "--base-url", baseUrl,
                    "servers", "register",
                    "--name", "survival-1",
                    "--address", "127.0.0.1:25565",
                    "--tag", "survival",
                    "--metadata", "host=blue.local");
            assertEquals(0, register.exitCode());

            var update = execute(
                    "--base-url", baseUrl,
                    "servers", "update", "survival-1",
                    "--address", "127.0.0.1:25566",
                    "--tag", "survival,canary",
                    "--capability", "large-payload",
                    "--weight", "20",
                    "--soft-capacity", "80",
                    "--hard-capacity", "100",
                    "--drain-mode", "true",
                    "--metadata", "host=green.local,group=survival-canary");

            assertEquals(0, update.exitCode());
            assertTrue(update.output().contains("\"address\":\"127.0.0.1:25566\""));
            assertTrue(update.output().contains("\"canary\""));
            assertTrue(update.output().contains("\"LARGE_PAYLOAD\""));
            assertTrue(update.output().contains("\"weight\":20"));
            assertTrue(update.output().contains("\"draining\":true"));
            assertTrue(update.output().contains("\"softCapacity\":80"));
            assertTrue(update.output().contains("\"hardCapacity\":100"));
            assertTrue(update.output().contains("\"group\":\"survival-canary\""));
        }
    }

    @Test
    void sendsBearerTokenWhenConfigured() throws Exception {
        var registry = new InMemoryServerRegistry();
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, new ProxyMetrics(), "secret")) {
            admin.start();
            var baseUrl = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();

            var unauthorized = execute("--base-url", baseUrl, "servers", "list");
            assertEquals(1, unauthorized.exitCode());
            assertTrue(unauthorized.output().contains("unauthorized"));

            var authorized = execute("--base-url", baseUrl, "--token", "secret", "servers", "list");
            assertEquals(0, authorized.exitCode());
            assertEquals("[]\n", authorized.output());

            var overviewUnauthorized = execute("--base-url", baseUrl, "overview");
            assertEquals(1, overviewUnauthorized.exitCode());
            assertTrue(overviewUnauthorized.output().contains("unauthorized"));

            var readiness = execute("--base-url", baseUrl, "ready");
            assertEquals(1, readiness.exitCode());
            assertTrue(readiness.output().contains("\"status\":\"NOT_READY\""));
        }
    }

    @Test
    void acceptsTlsStoreOptionsForAdminHttpsAndMtls() throws Exception {
        var directory = Files.createTempDirectory("strataproxy-admin-cli-tls");
        var serverKeyStore = directory.resolve("server-key.p12");
        var serverTrustStore = directory.resolve("server-trust.p12");
        var serverCertificate = directory.resolve("server.crt");
        var clientCertificate = directory.resolve("client.crt");
        var clientTrustStore = directory.resolve("client-trust.p12");
        var clientKeyStore = directory.resolve("client-key.p12");

        generateKeyPair(serverKeyStore, "serverpass", "server", "CN=localhost", "SAN=dns:localhost");
        generateKeyPair(clientKeyStore, "keypass", "client", "CN=strataproxy-admin-client");
        exportCertificate(serverKeyStore, "serverpass", "server", serverCertificate);
        exportCertificate(clientKeyStore, "keypass", "client", clientCertificate);
        importCertificate(clientTrustStore, "trustpass", "server", serverCertificate);
        importCertificate(serverTrustStore, "servertrustpass", "client", clientCertificate);

        var serverSslContext = sslContext(
                loadStore(serverKeyStore, "serverpass"),
                "serverpass",
                loadStore(serverTrustStore, "servertrustpass"));

        var registry = new InMemoryServerRegistry();
        try (var admin = new AdminHttpServer(
                new InetSocketAddress("localhost", 0),
                new AdminRegistryService(registry, NoopRegistryStore.INSTANCE),
                new ProxyMetrics(),
                "",
                50,
                true,
                serverSslContext,
                true)) {
            admin.start();
            var baseUrl = "https://localhost:" + admin.bindAddress().getPort();

            var health = execute(
                    "--base-url", baseUrl,
                    "--trust-store-path", clientTrustStore.toString(),
                    "--trust-store-password", "trustpass",
                    "--trust-store-type", "PKCS12",
                    "--key-store-path", clientKeyStore.toString(),
                    "--key-store-password", "keypass",
                    "--key-store-type", "PKCS12",
                    "health");

            assertEquals(0, health.exitCode());
            assertTrue(health.output().contains("\"status\":\"UP\""));
        }
    }

    @Test
    void printsOperationalOverview() throws Exception {
        var registry = new InMemoryServerRegistry();
        registry.register(new ServerDescriptor(
                "lobby-1",
                new InetSocketAddress("127.0.0.1", 25565),
                Set.of("lobby"),
                Set.of(),
                new ProtocolRange(0, Integer.MAX_VALUE, "any"),
                100,
                100,
                120,
                false,
                Map.of()));
        var metrics = new ProxyMetrics();
        metrics.networkTransport("nio", false);
        metrics.acceptedConnection();
        metrics.routedConnection();
        metrics.rejectedConnection("global_limit");
        metrics.frontendToBackendBytes("lobby-1", 1024);
        metrics.backendToFrontendBytes("lobby-1", 2048);
        metrics.compressionNegotiated("lobby-1", 256);
        metrics.compressionSample("lobby-1", 1000, 400, 0);
        metrics.packetAnomaly("initial-handshake-malformed-frame");
        metrics.eventLoopDelayNanos(1_000_000);
        metrics.pooledDirectMemoryBytes(4096);
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, metrics)) {
            admin.start();
            var baseUrl = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();

            var overview = execute("--base-url", baseUrl, "overview");

            assertEquals(0, overview.exitCode());
            assertTrue(overview.output().contains("status UP"));
            assertTrue(overview.output().contains("servers 1"));
            assertTrue(overview.output().contains("connections_active 1"));
            assertTrue(overview.output().contains("connections_routed_total 1"));
            assertTrue(overview.output().contains("connections_rejected_total 1"));
            assertTrue(overview.output().contains("connections_rejected_total{reason=\"global_limit\"} 1"));
            assertTrue(overview.output().contains("frontend_to_backend_bytes_total 1024"));
            assertTrue(overview.output().contains("backend_to_frontend_bytes_total 2048"));
            assertTrue(overview.output().contains("compression_negotiations_total 1"));
            assertTrue(overview.output().contains("compression_saved_bytes_total 600"));
            assertTrue(overview.output().contains("compression_ratio 0.4000"));
            assertTrue(overview.output().contains("packet_anomalies_total 1"));
            assertTrue(overview.output().contains("event_loop_delay_seconds 0.001000"));
            assertTrue(overview.output().contains("pooled_direct_memory_bytes 4096"));
            assertTrue(overview.output().contains("transport nio"));
            assertTrue(overview.output().contains("native_transport false"));
        }
    }

    @Test
    void checksOperationalSloGates() throws Exception {
        var registry = new InMemoryServerRegistry();
        registry.register(new ServerDescriptor(
                "lobby-1",
                new InetSocketAddress("127.0.0.1", 25565),
                Set.of("lobby"),
                Set.of(),
                new ProtocolRange(0, Integer.MAX_VALUE, "any"),
                100,
                100,
                120,
                false,
                Map.of()));
        var metrics = new ProxyMetrics();
        metrics.networkTransport("nio", false);
        metrics.acceptedConnection();
        metrics.eventLoopDelayNanos(1_000_000);
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, metrics)) {
            admin.start();
            var baseUrl = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();

            var slo = execute(
                    "--base-url", baseUrl,
                    "slo",
                    "--require-ready",
                    "--max-event-loop-delay-ms", "2",
                    "--max-active-connections", "1",
                    "--max-rejected", "0",
                    "--max-anomalies", "0");

            assertEquals(0, slo.exitCode());
            assertTrue(slo.output().contains("gate actual expected status\n"));
            assertTrue(slo.output().contains("health_status UP UP PASS"));
            assertTrue(slo.output().contains("readiness_status READY/1/1/200 READY/>=1/registered/200 PASS"));
            assertTrue(slo.output().contains("event_loop_delay_ms 1.000 <=2.000 PASS"));
            assertTrue(slo.output().contains("active_connections 1 <=1 PASS"));
        }
    }

    @Test
    void failsOperationalSloWhenGateIsExceeded() throws Exception {
        var registry = new InMemoryServerRegistry();
        var metrics = new ProxyMetrics();
        metrics.eventLoopDelayNanos(6_000_000);
        metrics.rejectedConnection();
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, metrics)) {
            admin.start();
            var baseUrl = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();

            var slo = execute(
                    "--base-url", baseUrl,
                    "slo",
                    "--max-event-loop-delay-ms", "5",
                    "--max-rejected", "0");

            assertEquals(1, slo.exitCode());
            assertTrue(slo.output().contains("event_loop_delay_ms 6.000 <=5.000 FAIL"));
            assertTrue(slo.output().contains("rejected_connections 1 <=0 FAIL"));
        }
    }

    @Test
    void failsOperationalSloWhenReadinessFails() throws Exception {
        var registry = new InMemoryServerRegistry();
        var metrics = new ProxyMetrics();
        metrics.networkTransport("nio", false);
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, metrics)) {
            admin.start();
            var baseUrl = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();

            var slo = execute("--base-url", baseUrl, "slo", "--require-ready");

            assertEquals(1, slo.exitCode());
            assertTrue(slo.output().contains("health_status UP UP PASS"));
            assertTrue(slo.output().contains("readiness_status NOT_READY/0/0/503 READY/>=1/registered/200 FAIL"));
        }
    }

    @Test
    void printsOperationalOverviewWhenPrometheusIsDisabled() throws Exception {
        var registry = new InMemoryServerRegistry();
        var metrics = new ProxyMetrics();
        metrics.networkTransport("nio", false);
        metrics.routedConnection();
        metrics.rejectedConnection("per_address_limit");
        metrics.frontendToBackendBytes(512);
        metrics.backendToFrontendBytes(1024);
        metrics.compressionSample(1000, 500, 0);
        metrics.packetAnomaly("initial-handshake-malformed-frame");
        metrics.eventLoopDelayNanos(2_000_000);
        metrics.pooledDirectMemoryBytes(8192);
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, metrics, "", 50, false)) {
            admin.start();
            var baseUrl = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();

            var overview = execute("--base-url", baseUrl, "overview");

            assertEquals(0, overview.exitCode());
            assertTrue(overview.output().contains("status UP"));
            assertTrue(overview.output().contains("connections_routed_total 1"));
            assertTrue(overview.output().contains("connections_rejected_total 1"));
            assertTrue(overview.output().contains("connections_rejected_total{reason=\"per_address_limit\"} 1"));
            assertTrue(overview.output().contains("frontend_to_backend_bytes_total 512"));
            assertTrue(overview.output().contains("backend_to_frontend_bytes_total 1024"));
            assertTrue(overview.output().contains("compression_saved_bytes_total 500"));
            assertTrue(overview.output().contains("packet_anomalies_total 1"));
            assertTrue(overview.output().contains("event_loop_delay_seconds 0.002000"));
            assertTrue(overview.output().contains("pooled_direct_memory_bytes 8192"));
        }
    }

    @Test
    void printsActivePlayerSessions() throws Exception {
        var registry = new InMemoryServerRegistry();
        var metrics = new ProxyMetrics();
        metrics.playerSessionStarted("Steve", "survival-1", "127.0.0.1:50000");
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, metrics)) {
            admin.start();
            var baseUrl = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();

            var players = execute("--base-url", baseUrl, "players");

            assertEquals(0, players.exitCode());
            assertTrue(players.output().contains("player server remote_address connected_at\n"));
            assertTrue(players.output().contains("Steve survival-1 127.0.0.1:50000 "));
        }
    }

    @Test
    void previewsRoutingDecisions() throws Exception {
        var registry = new InMemoryServerRegistry();
        registry.register(new ServerDescriptor(
                "survival-1",
                new InetSocketAddress("127.0.0.1", 25565),
                Set.of("survival", "forge"),
                Set.of(dev.strataproxy.api.server.ServerCapability.LARGE_PAYLOAD),
                new ProtocolRange(763, 763, "1.20.1"),
                100,
                100,
                120,
                false,
                Map.of("host", "survival.example.net")));
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, new ProxyMetrics())) {
            admin.start();
            var baseUrl = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();

            var selected = execute(
                    "--base-url", baseUrl,
                    "routes", "preview",
                    "--route", "survival.example.net",
                    "--protocol-version", "763",
                    "--remote-address", "127.0.0.1:50000",
                    "--tag", "survival",
                    "--capability", "large-payload");

            assertEquals(0, selected.exitCode());
            assertTrue(selected.output().contains("selected server score reason route protocol_version remote_address\n"));
            assertTrue(selected.output().contains("true survival-1 "));
            assertTrue(selected.output().contains("survival.example.net 763 /127.0.0.1:50000"));
            assertTrue(selected.output().contains("candidate eligible reason effective_weight selection_key\n"));
            assertTrue(selected.output().contains("survival-1 true - "));

            registry.updateDrainMode("survival-1", true);
            var rejected = execute(
                    "--base-url", baseUrl,
                    "routes", "preview",
                    "--route", "survival.example.net",
                    "--protocol-version", "763");

            assertEquals(1, rejected.exitCode());
            assertTrue(rejected.output().contains("false - 0.0000 no_healthy_backend_matched_route_constraints"));
            assertTrue(rejected.output().contains("survival-1 false draining 0.0000 inf"));
        }
    }

    @Test
    void printsCompressionAuditSummary() throws Exception {
        var registry = new InMemoryServerRegistry();
        registry.register(new ServerDescriptor(
                "survival-1",
                new InetSocketAddress("127.0.0.1", 25565),
                Set.of("survival"),
                Set.of(),
                new ProtocolRange(0, Integer.MAX_VALUE, "any"),
                100,
                100,
                120,
                false,
                Map.of()));
        var metrics = new ProxyMetrics();
        metrics.compressionNegotiated("survival-1", 256);
        metrics.compressionSample("survival-1", 1000, 400, 0);
        metrics.compressionSample("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, 500, 250, 0);
        metrics.compressionDecision("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, "threshold", 1024);
        metrics.compressionDecision("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, "threshold", 1024);
        metrics.compressionDecision("survival-1", ProxyMetrics.CompressionDirection.BACKEND_TO_FRONTEND, "bypass", -1);
        metrics.compressionRewrite("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, "rewritten", 2_000_000);
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, metrics)) {
            admin.start();
            var baseUrl = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();

            var compression = execute("--base-url", baseUrl, "compression");

            assertEquals(0, compression.exitCode());
            assertTrue(compression.output().contains("scope direction raw_bytes compressed_bytes saved_bytes ratio samples threshold negotiations"));
            assertTrue(compression.output().contains("global all 1500 650 850 0.4333 2 -1 1"));
            assertTrue(compression.output().contains("survival-1 all 1500 650 850 0.4333 2 256 0"));
            assertTrue(compression.output().contains("survival-1 frontend_to_backend 500 250 250 0.5000 1 -1 0"));
            assertTrue(compression.output().contains("survival-1 backend_to_frontend 0 0 0 1.0000 0 -1 0"));
            assertTrue(compression.output().contains("decision_scope direction action threshold count\n"));
            assertTrue(compression.output().contains("survival-1 frontend_to_backend threshold 1024 2"));
            assertTrue(compression.output().contains("survival-1 backend_to_frontend bypass -1 1"));
            assertTrue(compression.output().contains("rewrite_scope direction outcome count cpu_millis\n"));
            assertTrue(compression.output().contains("survival-1 frontend_to_backend rewritten 1 2.000"));
        }
    }

    @Test
    void printsCompressionAuditSummaryWhenPrometheusIsDisabled() throws Exception {
        var registry = new InMemoryServerRegistry();
        registry.register(new ServerDescriptor(
                "survival-1",
                new InetSocketAddress("127.0.0.1", 25565),
                Set.of("survival"),
                Set.of(),
                new ProtocolRange(0, Integer.MAX_VALUE, "any"),
                100,
                100,
                120,
                false,
                Map.of()));
        var metrics = new ProxyMetrics();
        metrics.compressionNegotiated("survival-1", 256);
        metrics.compressionSample("survival-1", 1000, 400, 0);
        metrics.compressionSample("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, 500, 250, 0);
        metrics.compressionDecision("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, "threshold", 1024);
        metrics.compressionRewrite("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, "rewritten", 3_000_000);
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, metrics, "", 50, false)) {
            admin.start();
            var baseUrl = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();

            var compression = execute("--base-url", baseUrl, "compression");

            assertEquals(0, compression.exitCode());
            assertTrue(compression.output().contains("global all 1500 650 850 0.4333 2 -1 1"));
            assertTrue(compression.output().contains("survival-1 all 1500 650 850 0.4333 2 256 0"));
            assertTrue(compression.output().contains("survival-1 frontend_to_backend 500 250 250 0.5000 1 -1 0"));
            assertTrue(compression.output().contains("survival-1 frontend_to_backend threshold 1024 1"));
            assertTrue(compression.output().contains("survival-1 frontend_to_backend rewritten 1 3.000"));
        }
    }

    @Test
    void printsPacketAnomalySummary() throws Exception {
        var registry = new InMemoryServerRegistry();
        var metrics = new ProxyMetrics();
        metrics.packetAnomaly("compression-audit-malformed");
        metrics.packetAnomaly("initial-handshake-malformed-frame", "127.0.0.1:50000", "", "frontend_to_backend", "HANDSHAKE", 0, 48, -1, "play.example.net");
        metrics.packetAnomaly("initial-handshake-malformed-frame");
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, metrics)) {
            admin.start();
            var baseUrl = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();

            var anomalies = execute("--base-url", baseUrl, "anomalies");

            assertEquals(0, anomalies.exitCode());
            assertTrue(anomalies.output().contains("rule count\n"));
            assertTrue(anomalies.output().contains("initial-handshake-malformed-frame 2"));
            assertTrue(anomalies.output().contains("compression-audit-malformed 1"));
            assertTrue(anomalies.output().indexOf("initial-handshake-malformed-frame 2")
                    < anomalies.output().indexOf("compression-audit-malformed 1"));

            var samples = execute("--base-url", baseUrl, "anomalies", "--samples");
            assertEquals(0, samples.exitCode());
            assertTrue(samples.output().contains("sequence timestamp rule server direction state packet_id raw_size compressed_size remote detail\n"));
            assertTrue(samples.output().contains("initial-handshake-malformed-frame"));
            assertTrue(samples.output().contains("frontend_to_backend"));
            assertTrue(samples.output().contains("HANDSHAKE"));
            assertTrue(samples.output().contains(" 0 48 -1 "));
            assertTrue(samples.output().contains("127.0.0.1:50000"));
            assertTrue(samples.output().contains("play.example.net"));
        }
    }

    @Test
    void printsPacketTrafficSummary() throws Exception {
        var registry = new InMemoryServerRegistry();
        var metrics = new ProxyMetrics();
        metrics.packetTraffic("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, "UNCOMPRESSED", 1, 100, 0);
        metrics.packetTraffic("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, "UNCOMPRESSED", 1, 50, 0);
        metrics.packetTraffic("survival-1", ProxyMetrics.CompressionDirection.BACKEND_TO_FRONTEND, "UNCOMPRESSED", 2, 300, 0);
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, metrics)) {
            admin.start();
            var baseUrl = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();

            var packets = execute("--base-url", baseUrl, "packets");

            assertEquals(0, packets.exitCode());
            assertTrue(packets.output().contains("server direction state packet_id packets raw_bytes compressed_bytes\n"));
            assertTrue(packets.output().contains("survival-1 backend_to_frontend UNCOMPRESSED 2 1 300 0"));
            assertTrue(packets.output().contains("survival-1 frontend_to_backend UNCOMPRESSED 1 2 150 0"));
            assertTrue(packets.output().indexOf("backend_to_frontend") < packets.output().indexOf("frontend_to_backend"));
        }
    }

    @Test
    void printsCustomPayloadSummary() throws Exception {
        var registry = new InMemoryServerRegistry();
        var metrics = new ProxyMetrics();
        metrics.customPayload("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, "FORGE_HANDSHAKE", "fml:handshake", 128, 0);
        metrics.customPayload(
                "survival-1",
                ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND,
                "FABRIC_HANDSHAKE",
                "fabric:registry/sync",
                256,
                64,
                "Steve",
                "127.0.0.1:50000",
                "CONFIGURATION",
                1);
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, metrics)) {
            admin.start();
            var baseUrl = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();

            var payloads = execute("--base-url", baseUrl, "mod-payloads");

            assertEquals(0, payloads.exitCode());
            assertTrue(payloads.output().contains("server direction kind channel packets payload_bytes compressed_bytes max_payload_bytes max_compressed_bytes first_seen last_seen\n"));
            assertTrue(payloads.output().contains("survival-1 frontend_to_backend FABRIC_HANDSHAKE fabric:registry/sync 1 256 64 256 64"));
            assertTrue(payloads.output().contains("survival-1 frontend_to_backend FORGE_HANDSHAKE fml:handshake 1 128 0 128 0"));

            var payloadSamples = execute("--base-url", baseUrl, "mod-payloads", "--samples");

            assertEquals(0, payloadSamples.exitCode());
            assertTrue(payloadSamples.output().contains("recent_samples\nsequence server direction kind channel payload_bytes compressed_bytes player remote state packet_id timestamp\n"));
            assertTrue(payloadSamples.output().contains("survival-1 frontend_to_backend FABRIC_HANDSHAKE fabric:registry/sync 256 64 Steve 127.0.0.1:50000 CONFIGURATION 1"));
        }
    }

    @Test
    void printsNativeRuntimeSummary() throws Exception {
        var registry = new InMemoryServerRegistry();
        var metrics = new ProxyMetrics();
        metrics.nativeRuntime(true, "Linux", "amd64", "/proc/cpuinfo", "jdk-aes-intrinsics", "jdk-deflater", true, false, java.util.Map.of("aes", true, "avx512f", false));
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, metrics)) {
            admin.start();
            var baseUrl = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();

            var nativeRuntime = execute("--base-url", baseUrl, "native");

            assertEquals(0, nativeRuntime.exitCode());
            assertTrue(nativeRuntime.output().contains("enabled os arch source tls_provider compression_provider prefer_native_transport require_native_transport\n"));
            assertTrue(nativeRuntime.output().contains("true Linux amd64 /proc/cpuinfo jdk-aes-intrinsics jdk-deflater true false"));
            assertTrue(nativeRuntime.output().contains("feature enabled\n"));
            assertTrue(nativeRuntime.output().contains("aes true"));
            assertTrue(nativeRuntime.output().contains("avx512f false"));
        }
    }

    @Test
    void printsRelayBackpressureSummary() throws Exception {
        var registry = new InMemoryServerRegistry();
        var metrics = new ProxyMetrics();
        metrics.relayBackpressure("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, 1024);
        metrics.relayBackpressure("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, 2048);
        metrics.relayBackpressure("lobby-1", ProxyMetrics.CompressionDirection.BACKEND_TO_FRONTEND, 512);
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, metrics)) {
            admin.start();
            var baseUrl = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();

            var backpressure = execute("--base-url", baseUrl, "backpressure");

            assertEquals(0, backpressure.exitCode());
            assertTrue(backpressure.output().contains("server direction events last_bytes_before_writable max_bytes_before_writable\n"));
            assertTrue(backpressure.output().contains("survival-1 frontend_to_backend 2 2048 2048"));
            assertTrue(backpressure.output().contains("lobby-1 backend_to_frontend 1 512 512"));
            assertTrue(backpressure.output().indexOf("survival-1") < backpressure.output().indexOf("lobby-1"));
        }
    }

    @Test
    void managesPayloadCaptures() throws Exception {
        var registry = new InMemoryServerRegistry();
        var metrics = new ProxyMetrics();
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, metrics)) {
            admin.start();
            var baseUrl = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();

            var start = execute(
                    "--base-url", baseUrl,
                    "captures", "start",
                    "--id", "cap-1",
                    "--server", "survival-1",
                    "--direction", "frontend_to_backend",
                    "--max-samples", "2",
                    "--max-bytes", "3",
                    "--duration-ms", "30000");

            assertEquals(0, start.exitCode());
            assertTrue(start.output().contains("id server direction max_samples max_bytes_per_sample expires_at sample_count\n"));
            assertTrue(start.output().contains("cap-1 survival-1 frontend_to_backend 2 3 "));

            metrics.payloadCaptured(
                    "survival-1",
                    ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND,
                    4,
                    -1,
                    new byte[] {1, 2, 3, 4},
                    "Steve",
                    "127.0.0.1:50000");

            var get = execute("--base-url", baseUrl, "captures", "get", "cap-1");

            assertEquals(0, get.exitCode());
            assertTrue(
                    get.output().contains("sequence capture_id server direction raw_bytes compressed_bytes player remote_address prefix_base64 timestamp\n"),
                    get.output());
            assertTrue(get.output().contains("1 cap-1 survival-1 frontend_to_backend 4 0 Steve 127.0.0.1:50000 AQID "), get.output());

            var list = execute("--base-url", baseUrl, "captures");

            assertEquals(0, list.exitCode());
            assertTrue(list.output().contains("sample_count"));
            assertTrue(list.output().contains("cap-1 survival-1 frontend_to_backend 2 3 "));

            var stop = execute("--base-url", baseUrl, "captures", "stop", "cap-1");

            assertEquals(0, stop.exitCode());
            assertTrue(stop.output().contains("\"removed\":true"));
        }
    }

    @Test
    void exportsDiagnosticReportJson() throws Exception {
        var registry = new InMemoryServerRegistry();
        registry.register(new ServerDescriptor(
                "survival-1",
                new InetSocketAddress("127.0.0.1", 25565),
                Set.of("survival"),
                Set.of(),
                new ProtocolRange(0, Integer.MAX_VALUE, "any"),
                100,
                100,
                120,
                false,
                Map.of()));
        var metrics = new ProxyMetrics();
        metrics.packetAnomaly("custom-payload-flood");
        metrics.packetTraffic("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, "UNCOMPRESSED", 1, 100, 0);
        metrics.customPayload("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, "FORGE_HANDSHAKE", "fml:handshake", 128, 0);
        metrics.relayBackpressure("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, 1024);
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, metrics)) {
            admin.start();
            var baseUrl = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();

            var diagnostics = execute("--base-url", baseUrl, "diagnostics");

            assertEquals(0, diagnostics.exitCode());
            assertTrue(diagnostics.output().contains("\"generatedAt\""));
            assertTrue(diagnostics.output().contains("\"overview\""));
            assertTrue(diagnostics.output().contains("\"servers\":[{\"name\":\"survival-1\""));
            assertTrue(diagnostics.output().contains("\"packetTraffic\""));
            assertTrue(diagnostics.output().contains("\"customPayloads\""));
            assertTrue(diagnostics.output().contains("\"packetAnomalies\""));
            assertTrue(diagnostics.output().contains("\"relayBackpressure\""));
            assertTrue(diagnostics.output().contains("\"kind\":\"FORGE_HANDSHAKE\",\"channel\":\"fml:handshake\",\"packets\":1"));
            assertTrue(diagnostics.output().contains("\"rule\":\"custom-payload-flood\",\"count\":1"));
            assertTrue(diagnostics.output().contains("\"server\":\"survival-1\",\"direction\":\"frontend_to_backend\",\"events\":1"));
        }
    }

    @Test
    void printsEmptyPacketAnomalySummary() throws Exception {
        var registry = new InMemoryServerRegistry();
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, new ProxyMetrics())) {
            admin.start();
            var baseUrl = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();

            var anomalies = execute("--base-url", baseUrl, "anomalies");

            assertEquals(0, anomalies.exitCode());
            assertEquals("rule count\n", anomalies.output());
        }
    }

    private static Result execute(String... args) {
        var originalOut = System.out;
        var output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            var exitCode = new CommandLine(new StrataProxyAdminCli()).execute(args);
            return new Result(exitCode, output.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
        }
    }

    private static void generateKeyPair(Path store, String password, String alias, String distinguishedName, String... extensions) throws Exception {
        var args = new ArrayList<>(List.of(
                "-genkeypair",
                "-alias", alias,
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "1",
                "-keystore", store.toString(),
                "-storetype", "PKCS12",
                "-storepass", password,
                "-keypass", password,
                "-dname", distinguishedName));
        for (var extension : extensions) {
            args.add("-ext");
            args.add(extension);
        }
        keytool(args);
    }

    private static void exportCertificate(Path store, String password, String alias, Path certificate) throws Exception {
        keytool(List.of(
                "-exportcert",
                "-rfc",
                "-alias", alias,
                "-keystore", store.toString(),
                "-storepass", password,
                "-file", certificate.toString()));
    }

    private static void importCertificate(Path store, String password, String alias, Path certificate) throws Exception {
        keytool(List.of(
                "-importcert",
                "-noprompt",
                "-alias", alias,
                "-file", certificate.toString(),
                "-keystore", store.toString(),
                "-storetype", "PKCS12",
                "-storepass", password));
    }

    private static KeyStore loadStore(Path path, String password) throws Exception {
        var store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(path)) {
            store.load(input, password.toCharArray());
        }
        return store;
    }

    private static void keytool(List<String> args) throws Exception {
        var executable = Path.of(System.getProperty("java.home"), "bin", windows() ? "keytool.exe" : "keytool").toString();
        var command = new ArrayList<String>();
        command.add(executable);
        command.addAll(args);
        var process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("keytool failed with exit code " + exitCode + ": " + output);
        }
    }

    private static boolean windows() {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows");
    }

    private static SSLContext sslContext(KeyStore keyStore, String keyPassword, KeyStore trustStore) throws Exception {
        var keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, keyPassword.toCharArray());
        var trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);
        var context = SSLContext.getInstance("TLS");
        context.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);
        return context;
    }

    private record Result(int exitCode, String output) {
    }
}

package dev.strataproxy.admin;

import dev.strataproxy.observability.ProxyMetrics;
import dev.strataproxy.registry.InMemoryServerRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collection;
import java.util.List;
import javax.net.ssl.SSLContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AdminHttpServerTest {
    @Test
    void closeIsIdempotent() throws Exception {
        var registry = new InMemoryServerRegistry();
        var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, new ProxyMetrics());
        admin.start();

        admin.close();
        admin.close();
    }

    @Test
    void startsWithHttpsContext() throws Exception {
        var registry = new InMemoryServerRegistry();
        try (var admin = new AdminHttpServer(
                new InetSocketAddress("127.0.0.1", 0),
                new AdminRegistryService(registry, NoopRegistryStore.INSTANCE),
                new ProxyMetrics(),
                "",
                50,
                true,
                SSLContext.getDefault(),
                false)) {
            admin.start();

            assertTrue(admin.bindAddress().getPort() > 0);
        }
    }

    @Test
    void supportsDynamicServerLifecycle() throws Exception {
        var registry = new InMemoryServerRegistry();
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, new ProxyMetrics())) {
            admin.start();
            var base = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();
            var client = HttpClient.newHttpClient();

            var register = post(client, base + "/servers", """
                    {
                      "name": "survival-1",
                      "address": "127.0.0.1:25566",
                      "tags": ["survival"],
                      "capabilities": ["large-payload"],
                      "protocolRange": "763",
                      "metadata": {"host": "survival.local"}
                    }
                    """);
            assertEquals(201, register.statusCode());
            assertTrue(register.body().contains("survival-1"));

            var health = post(client, base + "/servers/survival-1/health", """
                    {"status": "DEGRADED", "backendPingMillis": 42, "recentFailureRate": 0.1, "reason": "test"}
                    """);
            assertEquals(200, health.statusCode());
            assertTrue(health.body().contains("DEGRADED"));

            var load = post(client, base + "/servers/survival-1/load", """
                    {"players": 12, "softCapacity": 100, "hardCapacity": 120, "packetsPerSecond": 300}
                    """);
            assertEquals(200, load.statusCode());
            assertTrue(load.body().contains("\"players\":12"));

            var drain = post(client, base + "/servers/survival-1/drain", "{}");
            assertEquals(200, drain.statusCode());
            assertTrue(drain.body().contains("true"));

            var drained = client.send(HttpRequest.newBuilder(URI.create(base + "/servers/survival-1")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, drained.statusCode());
            assertTrue(drained.body().contains("\"draining\":true"));

            var undrain = post(client, base + "/servers/survival-1/undrain", "{}");
            assertEquals(200, undrain.statusCode());
            assertTrue(undrain.body().contains("\"draining\":false"));

            var remove = delete(client, base + "/servers/survival-1");
            assertEquals(200, remove.statusCode());
        }
    }

    @Test
    void protectsAdminEndpointsWhenBearerTokenIsConfigured() throws Exception {
        var registry = new InMemoryServerRegistry();
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, new ProxyMetrics(), "secret-token")) {
            admin.start();
            var base = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();
            var client = HttpClient.newHttpClient();

            var health = client.send(HttpRequest.newBuilder(URI.create(base + "/healthz")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
            var readiness = client.send(HttpRequest.newBuilder(URI.create(base + "/readyz")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(503, readiness.statusCode());
            assertTrue(readiness.body().contains("\"status\":\"NOT_READY\""));

            var unauthorized = post(client, base + "/servers", """
                    {"name":"blocked","address":"127.0.0.1:25565"}
                    """);
            assertEquals(401, unauthorized.statusCode());

            var authorized = post(client, base + "/servers", """
                    {"name":"allowed","address":"127.0.0.1:25565"}
                    """, "secret-token");
            assertEquals(201, authorized.statusCode());
        }
    }

    @Test
    void readinessReflectsRoutableBackendAvailability() throws Exception {
        var registry = new InMemoryServerRegistry();
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, new ProxyMetrics())) {
            admin.start();
            var base = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();
            var client = HttpClient.newHttpClient();

            var empty = client.send(HttpRequest.newBuilder(URI.create(base + "/readyz")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(503, empty.statusCode());
            assertTrue(empty.body().contains("\"status\":\"NOT_READY\""));
            assertTrue(empty.body().contains("\"readyServers\":0"));
            assertTrue(empty.body().contains("\"registeredServers\":0"));

            registry.register(server("survival-1", false, 100, 120));
            var ready = client.send(HttpRequest.newBuilder(URI.create(base + "/readyz")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ready.statusCode());
            assertTrue(ready.body().contains("\"status\":\"READY\""));
            assertTrue(ready.body().contains("\"readyServers\":1"));
            assertTrue(ready.body().contains("\"registeredServers\":1"));
        }
    }

    @Test
    void readinessRejectsDrainingDownAndHardFullBackends() throws Exception {
        var registry = new InMemoryServerRegistry();
        registry.register(server("drain-1", true, 100, 120));
        registry.register(server("down-1", false, 100, 120));
        registry.register(server("full-1", false, 1, 1));
        registry.updateHealth("down-1", new dev.strataproxy.api.server.ServerHealth(
                dev.strataproxy.api.server.ServerHealthStatus.DOWN,
                -1,
                1.0d,
                "test",
                java.time.Instant.now()));
        registry.updateLoad("full-1", new dev.strataproxy.api.server.ServerLoad(1, 1, 1, 0, 0, 0, 0));
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, new ProxyMetrics())) {
            admin.start();
            var base = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();
            var client = HttpClient.newHttpClient();

            var response = client.send(HttpRequest.newBuilder(URI.create(base + "/readyz")).GET().build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(503, response.statusCode());
            assertTrue(response.body().contains("\"status\":\"NOT_READY\""));
            assertTrue(response.body().contains("\"readyServers\":0"));
            assertTrue(response.body().contains("\"registeredServers\":3"));
        }
    }

    @Test
    void previewsRoutingDecisionWithoutMutatingRegistry() throws Exception {
        var registry = new InMemoryServerRegistry();
        registry.register(new dev.strataproxy.api.server.ServerDescriptor(
                "survival-1",
                new InetSocketAddress("127.0.0.1", 25565),
                java.util.Set.of("survival", "forge"),
                java.util.Set.of(dev.strataproxy.api.server.ServerCapability.LARGE_PAYLOAD),
                new dev.strataproxy.api.server.ProtocolRange(763, 763, "1.20.1"),
                100,
                100,
                120,
                false,
                java.util.Map.of("host", "survival.example.net")));
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, new ProxyMetrics())) {
            admin.start();
            var base = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();
            var client = HttpClient.newHttpClient();

            var response = client.send(HttpRequest.newBuilder(URI.create(base
                    + "/routes/preview?route=survival.example.net&protocolVersion=763&remoteAddress=127.0.0.1:50000&tag=survival&capability=large-payload"))
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"selected\":true"));
            assertTrue(response.body().contains("\"server\":\"survival-1\""));
            assertTrue(response.body().contains("\"protocolVersion\":763"));
            assertTrue(response.body().contains("\"candidates\":["));
            assertTrue(response.body().contains("\"eligible\":true"));
            assertTrue(response.body().contains("\"effectiveWeight\""));
        }
    }

    @Test
    void previewsRejectedRoutingDecision() throws Exception {
        var registry = new InMemoryServerRegistry();
        registry.register(server("survival-1", true, 100, 120));
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, new ProxyMetrics())) {
            admin.start();
            var base = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();
            var client = HttpClient.newHttpClient();

            var response = client.send(HttpRequest.newBuilder(URI.create(base + "/routes/preview?route=survival-1&protocolVersion=763"))
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"selected\":false"));
            assertTrue(response.body().contains("no healthy backend matched route constraints"));
            assertTrue(response.body().contains("\"reason\":\"draining\""));
        }
    }

    @Test
    void routePreviewRequiresProtocolVersion() throws Exception {
        var registry = new InMemoryServerRegistry();
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, new ProxyMetrics())) {
            admin.start();
            var base = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();
            var client = HttpClient.newHttpClient();

            var response = client.send(HttpRequest.newBuilder(URI.create(base + "/routes/preview?route=survival"))
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(400, response.statusCode());
            assertTrue(response.body().contains("protocolVersion query parameter is required"));
        }
    }

    @Test
    void rejectsMalformedJsonAsBadRequest() throws Exception {
        var registry = new InMemoryServerRegistry();
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, new ProxyMetrics())) {
            admin.start();
            var base = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();
            var client = HttpClient.newHttpClient();

            var response = post(client, base + "/servers", "{");

            assertEquals(400, response.statusCode());
            assertTrue(response.body().contains("invalid json request"));
        }
    }

    @Test
    void rejectsInvalidServerRegistrationRequest() throws Exception {
        var registry = new InMemoryServerRegistry();
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, new ProxyMetrics())) {
            admin.start();
            var base = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();
            var client = HttpClient.newHttpClient();

            var blankName = post(client, base + "/servers", """
                    {"name":" ","address":"127.0.0.1:25565"}
                    """);
            var badCapacity = post(client, base + "/servers", """
                    {"name":"bad-capacity","address":"127.0.0.1:25565","softCapacity":20,"hardCapacity":10}
                    """);

            assertEquals(400, blankName.statusCode());
            assertTrue(blankName.body().contains("server name must not be blank"));
            assertEquals(400, badCapacity.statusCode());
            assertTrue(badCapacity.body().contains("softCapacity"));
        }
    }

    @Test
    void healthAndLoadUpdatesReturnNotFoundForMissingServer() throws Exception {
        var registry = new InMemoryServerRegistry();
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, new ProxyMetrics())) {
            admin.start();
            var base = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();
            var client = HttpClient.newHttpClient();

            var health = post(client, base + "/servers/missing/health", """
                    {"status":"UP"}
                    """);
            var load = post(client, base + "/servers/missing/load", """
                    {"players":1}
                    """);

            assertEquals(404, health.statusCode());
            assertTrue(health.body().contains("server not found"));
            assertEquals(404, load.statusCode());
            assertTrue(load.body().contains("server not found"));
        }
    }

    @Test
    void rejectsInvalidLoadValues() throws Exception {
        var registry = new InMemoryServerRegistry();
        registry.register(new dev.strataproxy.api.server.ServerDescriptor(
                "load-1",
                new InetSocketAddress("127.0.0.1", 25565),
                java.util.Set.of(),
                java.util.Set.of(),
                new dev.strataproxy.api.server.ProtocolRange(0, Integer.MAX_VALUE, "any"),
                100,
                100,
                120,
                false,
                java.util.Map.of()));
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, new ProxyMetrics())) {
            admin.start();
            var base = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();
            var client = HttpClient.newHttpClient();

            var response = post(client, base + "/servers/load-1/load", """
                    {"players":1,"inboundBytesPerSecond":-1}
                    """);

            assertEquals(400, response.statusCode());
            assertTrue(response.body().contains("traffic values"));
        }
    }

    @Test
    void exportsActivePlayerSessions() throws Exception {
        var registry = new InMemoryServerRegistry();
        var metrics = new ProxyMetrics();
        metrics.playerSessionStarted("Steve", "survival-1", "127.0.0.1:50000");
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, metrics)) {
            admin.start();
            var base = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();
            var client = HttpClient.newHttpClient();

            var response = client.send(HttpRequest.newBuilder(URI.create(base + "/player-sessions")).GET().build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"active\":1"));
            assertTrue(response.body().contains("\"player\":\"Steve\""));
            assertTrue(response.body().contains("\"server\":\"survival-1\""));
            assertTrue(response.body().contains("\"remoteAddress\":\"127.0.0.1:50000\""));
        }
    }

    @Test
    void rollsBackRegisterWhenRegistryPersistenceFails() throws Exception {
        var registry = new InMemoryServerRegistry();
        try (var admin = new AdminHttpServer(
                new InetSocketAddress("127.0.0.1", 0),
                new AdminRegistryService(registry, new FailingRegistryStore()),
                new ProxyMetrics(),
                "")) {
            admin.start();
            var base = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();
            var client = HttpClient.newHttpClient();

            var response = post(client, base + "/servers", """
                    {"name":"rollback-1","address":"127.0.0.1:25565"}
                    """);

            assertEquals(500, response.statusCode());
            assertTrue(response.body().contains("persist failed"));
            assertTrue(registry.snapshot().isEmpty());
        }
    }

    @Test
    void postServersReplacesExistingDescriptorAndKeepsRuntimeState() throws Exception {
        var registry = new InMemoryServerRegistry();
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, new ProxyMetrics())) {
            admin.start();
            var base = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();
            var client = HttpClient.newHttpClient();

            var create = post(client, base + "/servers", """
                    {
                      "name": "survival-1",
                      "address": "127.0.0.1:25565",
                      "tags": ["blue"],
                      "softCapacity": 100,
                      "hardCapacity": 120,
                      "metadata": {"host": "blue.local"}
                    }
                    """);
            assertEquals(201, create.statusCode());
            var health = post(client, base + "/servers/survival-1/health", """
                    {"status": "DEGRADED", "backendPingMillis": 42, "recentFailureRate": 0.2, "reason": "canary"}
                    """);
            var load = post(client, base + "/servers/survival-1/load", """
                    {"players": 12, "softCapacity": 100, "hardCapacity": 120, "inboundBytesPerSecond": 1024}
                    """);
            assertEquals(200, health.statusCode());
            assertEquals(200, load.statusCode());

            var replace = post(client, base + "/servers", """
                    {
                      "name": "survival-1",
                      "address": "127.0.0.1:25566",
                      "tags": ["green", "survival"],
                      "softCapacity": 200,
                      "hardCapacity": 240,
                      "drainMode": true,
                      "metadata": {"host": "green.local"}
                    }
                    """);
            assertEquals(200, replace.statusCode());
            assertTrue(replace.body().contains("\"address\":\"127.0.0.1:25566\""));
            assertTrue(replace.body().contains("\"green\""));
            assertTrue(replace.body().contains("\"draining\":true"));
            assertTrue(replace.body().contains("\"players\":12"));
            assertTrue(replace.body().contains("\"softCapacity\":200"));
            assertTrue(replace.body().contains("\"hardCapacity\":240"));
            assertTrue(replace.body().contains("\"status\":\"DEGRADED\""));

            var fetched = client.send(HttpRequest.newBuilder(URI.create(base + "/servers/survival-1")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, fetched.statusCode());
            assertTrue(fetched.body().contains("\"address\":\"127.0.0.1:25566\""));
            assertTrue(fetched.body().contains("\"host\":\"green.local\""));
            assertTrue(fetched.body().contains("\"players\":12"));
            assertTrue(fetched.body().contains("\"status\":\"DEGRADED\""));
        }
    }

    @Test
    void patchServerPartiallyUpdatesDescriptorAndKeepsRuntimeState() throws Exception {
        var registry = new InMemoryServerRegistry();
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, new ProxyMetrics())) {
            admin.start();
            var base = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();
            var client = HttpClient.newHttpClient();

            var create = post(client, base + "/servers", """
                    {
                      "name": "survival-1",
                      "address": "127.0.0.1:25565",
                      "tags": ["blue"],
                      "weight": 100,
                      "softCapacity": 100,
                      "hardCapacity": 120,
                      "metadata": {"host": "blue.local"}
                    }
                    """);
            assertEquals(201, create.statusCode());
            assertEquals(200, post(client, base + "/servers/survival-1/health", """
                    {"status": "DEGRADED", "backendPingMillis": 42, "recentFailureRate": 0.2, "reason": "canary"}
                    """).statusCode());
            assertEquals(200, post(client, base + "/servers/survival-1/load", """
                    {"players": 12, "softCapacity": 100, "hardCapacity": 120, "packetsPerSecond": 300}
                    """).statusCode());

            var update = patch(client, base + "/servers/survival-1", """
                    {
                      "address": "127.0.0.1:25566",
                      "tags": ["green", "survival"],
                      "capabilities": ["large-payload"],
                      "weight": 25,
                      "softCapacity": 40,
                      "hardCapacity": 50,
                      "drainMode": true,
                      "metadata": {"host": "green.local", "route": "survival"}
                    }
                    """);

            assertEquals(200, update.statusCode());
            assertTrue(update.body().contains("\"address\":\"127.0.0.1:25566\""));
            assertTrue(update.body().contains("\"green\""));
            assertTrue(update.body().contains("\"LARGE_PAYLOAD\""));
            assertTrue(update.body().contains("\"weight\":25"));
            assertTrue(update.body().contains("\"draining\":true"));
            assertTrue(update.body().contains("\"players\":12"));
            assertTrue(update.body().contains("\"status\":\"DEGRADED\""));
            assertTrue(update.body().contains("\"host\":\"green.local\""));
        }
    }

    @Test
    void patchServerReturnsNotFoundForMissingServer() throws Exception {
        var registry = new InMemoryServerRegistry();
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, new ProxyMetrics())) {
            admin.start();
            var base = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();
            var client = HttpClient.newHttpClient();

            var response = patch(client, base + "/servers/missing", "{\"weight\":10}");

            assertEquals(404, response.statusCode());
            assertTrue(response.body().contains("server not found"));
        }
    }

    @Test
    void exportsStructuredDiagnosticReport() throws Exception {
        var registry = new InMemoryServerRegistry();
        registry.register(new dev.strataproxy.api.server.ServerDescriptor(
                "survival-1",
                new InetSocketAddress("127.0.0.1", 25565),
                java.util.Set.of("survival"),
                java.util.Set.of(),
                new dev.strataproxy.api.server.ProtocolRange(0, Integer.MAX_VALUE, "any"),
                100,
                100,
                120,
                false,
                java.util.Map.of("host", "survival.local")));
        var metrics = new ProxyMetrics();
        metrics.networkTransport("nio", false);
        metrics.routedConnection();
        metrics.rejectedConnection("global_limit");
        metrics.rejectedConnection("per_address_limit");
        metrics.frontendToBackendBytes("survival-1", 1024);
        metrics.backendToFrontendBytes("survival-1", 2048);
        metrics.compressionNegotiated("survival-1", 256);
        metrics.compressionSample("survival-1", 1000, 400, 0);
        metrics.compressionDecision("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, "threshold", 1024);
        metrics.packetTraffic("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, "UNCOMPRESSED", 1, 128, 0);
        metrics.customPayload("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, "FORGE_HANDSHAKE", "fml:handshake", 256, 0);
        metrics.relayBackpressure("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, 512);
        metrics.startPayloadCapture("cap-1", "survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, 4, 8, java.time.Instant.now().plusSeconds(30));
        metrics.packetAnomaly("initial-handshake-malformed-frame", "127.0.0.1:50000", "survival-1", "frontend_to_backend", "HANDSHAKE", 0, 48, -1, "play.example.net");
        metrics.playerSessionStarted("Steve", "survival-1", "127.0.0.1:50000");
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, metrics, "", 10, false)) {
            admin.start();
            var base = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();
            var client = HttpClient.newHttpClient();

            var response = client.send(HttpRequest.newBuilder(URI.create(base + "/diagnostic-report")).GET().build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"generatedAt\""));
            assertTrue(response.body().contains("\"overview\""));
            assertTrue(response.body().contains("\"rejectedConnectionsByReason\":{\"global_limit\":1,\"per_address_limit\":1}"));
            assertTrue(response.body().contains("\"admission\":{\"rejectedConnections\":2,\"rejectedConnectionsByReason\":{\"global_limit\":1,\"per_address_limit\":1}}"));
            assertTrue(response.body().contains("\"servers\":[{\"name\":\"survival-1\""));
            assertTrue(response.body().contains("\"compression\""));
            assertTrue(response.body().contains("\"scope\":\"survival-1\",\"direction\":\"all\",\"rawBytes\":1000,\"compressedBytes\":400,\"savedBytes\":600"));
            assertTrue(response.body().contains("\"action\":\"threshold\",\"threshold\":1024,\"count\":1"));
            assertTrue(response.body().contains("\"packetTraffic\""));
            assertTrue(response.body().contains("\"packetId\":1,\"packets\":1,\"rawBytes\":128"));
            assertTrue(response.body().contains("\"customPayloads\""));
            assertTrue(response.body().contains("\"kind\":\"FORGE_HANDSHAKE\",\"channel\":\"fml:handshake\",\"packets\":1,\"payloadBytes\":256"));
            assertTrue(response.body().contains("\"packetAnomalies\""));
            assertTrue(response.body().contains("\"rule\":\"initial-handshake-malformed-frame\",\"count\":1"));
            assertTrue(response.body().contains("\"relayBackpressure\""));
            assertTrue(response.body().contains("\"events\":1,\"lastBytesBeforeWritable\":512,\"maxBytesBeforeWritable\":512"));
            assertTrue(response.body().contains("\"payloadCaptures\""));
            assertTrue(response.body().contains("\"id\":\"cap-1\",\"server\":\"survival-1\",\"direction\":\"frontend_to_backend\""));
            assertTrue(response.body().contains("\"playerSessions\""));
            assertTrue(response.body().contains("\"player\":\"Steve\",\"server\":\"survival-1\""));
            assertTrue(response.body().contains("\"remoteAddress\":\"127.0.0.1:50000\""));
            assertTrue(response.body().contains("\"detail\":\"play.example.net\""));
        }
    }

    @Test
    void managesPayloadCaptures() throws Exception {
        var registry = new InMemoryServerRegistry();
        var metrics = new ProxyMetrics();
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, metrics)) {
            admin.start();
            var base = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();
            var client = HttpClient.newHttpClient();

            var create = post(client, base + "/payload-captures", """
                    {
                      "id": "cap-1",
                      "server": "survival-1",
                      "direction": "frontend_to_backend",
                      "maxSamples": 2,
                      "maxBytesPerSample": 3,
                      "durationMillis": 30000
                    }
                    """);
            assertEquals(201, create.statusCode());
            assertTrue(create.body().contains("\"id\":\"cap-1\""));

            metrics.payloadCaptured(
                    "survival-1",
                    ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND,
                    4,
                    -1,
                    new byte[] {1, 2, 3, 4},
                    "Steve",
                    "127.0.0.1:50000");
            var export = client.send(HttpRequest.newBuilder(URI.create(base + "/payload-captures/cap-1")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, export.statusCode());
            assertTrue(export.body().contains("\"sampleCount\":1"));
            assertTrue(export.body().contains("\"prefixBase64\":\"AQID\""));
            assertTrue(export.body().contains("\"player\":\"Steve\""));
            assertTrue(export.body().contains("\"remoteAddress\":\"127.0.0.1:50000\""));

            var list = client.send(HttpRequest.newBuilder(URI.create(base + "/payload-captures")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, list.statusCode());
            assertTrue(list.body().contains("\"captures\""));
            assertTrue(list.body().contains("\"sampleCount\":1"));

            var delete = delete(client, base + "/payload-captures/cap-1");
            assertEquals(200, delete.statusCode());
            assertTrue(delete.body().contains("\"removed\":true"));
        }
    }

    @Test
    void exposesRuntimeMetrics() throws Exception {
        var registry = new InMemoryServerRegistry();
        var metrics = new ProxyMetrics();
        metrics.eventLoopDelayNanos(1_000_000);
        metrics.pooledDirectMemoryBytes(2048);
        metrics.networkTransport("nio", false);
        metrics.rejectedConnection("global_limit");
        metrics.rejectedConnection("per_address_limit");
        metrics.packetAnomaly("initial-handshake-malformed-frame");
        metrics.packetTraffic("metrics-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, "UNCOMPRESSED", 1, 64, 0);
        metrics.customPayload("metrics-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, "FABRIC_HANDSHAKE", "fabric:registry/sync", 128, 64);
        metrics.relayBackpressure("metrics-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, 1024);
        metrics.compressionSample(1000, 500, 2_000_000_000L);
        metrics.compressionNegotiated("metrics-1", 256);
        metrics.compressionDecision("metrics-1", ProxyMetrics.CompressionDirection.BACKEND_TO_FRONTEND, "threshold", 1024);
        metrics.compressionRewrite("metrics-1", ProxyMetrics.CompressionDirection.BACKEND_TO_FRONTEND, "rewritten", 2_000_000);
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, metrics)) {
            admin.start();
            var base = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();
            var client = HttpClient.newHttpClient();

            var response = client.send(HttpRequest.newBuilder(URI.create(base + "/metrics")).GET().build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("strataproxy_event_loop_delay_seconds"));
            assertTrue(response.body().contains("strataproxy_pooled_direct_memory_bytes 2048"));
            assertTrue(response.body().contains("strataproxy_network_transport_info{transport=\"nio\",native=\"false\"} 1"));
            assertTrue(response.body().contains("strataproxy_connections_rejected_total 2"));
            assertTrue(response.body().contains("strataproxy_connections_rejected_total{reason=\"global_limit\"} 1"));
            assertTrue(response.body().contains("strataproxy_connections_rejected_total{reason=\"per_address_limit\"} 1"));
            assertTrue(response.body().contains("strataproxy_jvm_heap_used_bytes"));
            assertTrue(response.body().contains("strataproxy_jvm_threads_live"));
            assertTrue(response.body().contains("strataproxy_jvm_gc_collections_total"));
            assertTrue(response.body().contains("strataproxy_packet_anomalies_total{rule=\"initial-handshake-malformed-frame\"} 1"));
            assertTrue(response.body().contains("strataproxy_packet_traffic_packets_total{server=\"metrics-1\",direction=\"frontend_to_backend\",state=\"UNCOMPRESSED\",packet_id=\"1\"} 1"));
            assertTrue(response.body().contains("strataproxy_packet_traffic_raw_bytes_total{server=\"metrics-1\",direction=\"frontend_to_backend\",state=\"UNCOMPRESSED\",packet_id=\"1\"} 64"));
            assertTrue(response.body().contains("strataproxy_custom_payload_packets_total{server=\"metrics-1\",direction=\"frontend_to_backend\",kind=\"FABRIC_HANDSHAKE\",channel=\"fabric:registry/sync\"} 1"));
            assertTrue(response.body().contains("strataproxy_custom_payload_bytes_total{server=\"metrics-1\",direction=\"frontend_to_backend\",kind=\"FABRIC_HANDSHAKE\",channel=\"fabric:registry/sync\"} 128"));
            assertTrue(response.body().contains("strataproxy_custom_payload_compressed_bytes_total{server=\"metrics-1\",direction=\"frontend_to_backend\",kind=\"FABRIC_HANDSHAKE\",channel=\"fabric:registry/sync\"} 64"));
            assertTrue(response.body().contains("strataproxy_custom_payload_max_bytes{server=\"metrics-1\",direction=\"frontend_to_backend\",kind=\"FABRIC_HANDSHAKE\",channel=\"fabric:registry/sync\"} 128"));
            assertTrue(response.body().contains("strataproxy_custom_payload_max_compressed_bytes{server=\"metrics-1\",direction=\"frontend_to_backend\",kind=\"FABRIC_HANDSHAKE\",channel=\"fabric:registry/sync\"} 64"));
            assertTrue(response.body().contains("strataproxy_relay_backpressure_events_total{server=\"metrics-1\",direction=\"frontend_to_backend\"} 1"));
            assertTrue(response.body().contains("strataproxy_relay_backpressure_last_bytes_before_writable{server=\"metrics-1\",direction=\"frontend_to_backend\"} 1024"));
            assertTrue(response.body().contains("strataproxy_compression_negotiations_total 1"));
            assertTrue(response.body().contains("strataproxy_compression_raw_bytes_total 1000"));
            assertTrue(response.body().contains("strataproxy_compression_compressed_bytes_total 500"));
            assertTrue(response.body().contains("strataproxy_compression_saved_bytes_total 500"));
            assertTrue(response.body().contains("strataproxy_compression_cpu_seconds_total 2.0"));
            assertTrue(response.body().contains("strataproxy_compression_ratio 0.5"));
            assertTrue(response.body().contains("strataproxy_compression_decisions_total{server=\"metrics-1\",direction=\"backend_to_frontend\",action=\"threshold\",threshold=\"1024\"} 1"));
            assertTrue(response.body().contains("strataproxy_compression_rewrites_total{server=\"metrics-1\",direction=\"backend_to_frontend\",outcome=\"rewritten\"} 1"));
            assertTrue(response.body().contains("strataproxy_compression_rewrite_cpu_seconds_total{server=\"metrics-1\",direction=\"backend_to_frontend\",outcome=\"rewritten\"} 0.002"));
        }
    }

    @Test
    void canDisablePrometheusMetricsWithoutDisablingStructuredReports() throws Exception {
        var registry = new InMemoryServerRegistry();
        var metrics = new ProxyMetrics();
        metrics.packetTraffic("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, "UNCOMPRESSED", 1, 64, 0);
        metrics.routedConnection();
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, metrics, "", 50, false)) {
            admin.start();
            var base = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();
            var client = HttpClient.newHttpClient();

            var prometheus = client.send(HttpRequest.newBuilder(URI.create(base + "/metrics")).GET().build(), HttpResponse.BodyHandlers.ofString());
            var packetTraffic = client.send(HttpRequest.newBuilder(URI.create(base + "/packet-traffic")).GET().build(), HttpResponse.BodyHandlers.ofString());
            var overview = client.send(HttpRequest.newBuilder(URI.create(base + "/overview")).GET().build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(404, prometheus.statusCode());
            assertTrue(prometheus.body().contains("prometheus metrics disabled"));
            assertEquals(200, packetTraffic.statusCode());
            assertTrue(packetTraffic.body().contains("\"totalPackets\":1"));
            assertEquals(200, overview.statusCode());
            assertTrue(overview.body().contains("\"status\":\"UP\""));
            assertTrue(overview.body().contains("\"routedConnections\":1"));
        }
    }

    @Test
    void exposesPacketAnomalyReport() throws Exception {
        var registry = new InMemoryServerRegistry();
        var metrics = new ProxyMetrics();
        metrics.packetAnomaly("custom-payload-flood");
        metrics.packetAnomaly("initial-handshake-malformed-frame", "127.0.0.1:50000", "", "frontend_to_backend", "HANDSHAKE", 0, 48, -1, "play.example.net");
        metrics.packetAnomaly("initial-handshake-malformed-frame");
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, metrics)) {
            admin.start();
            var base = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();
            var client = HttpClient.newHttpClient();

            var response = client.send(HttpRequest.newBuilder(URI.create(base + "/packet-anomalies")).GET().build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"total\":3"));
            assertTrue(response.body().contains("\"rule\":\"initial-handshake-malformed-frame\",\"count\":2"));
            assertTrue(response.body().contains("\"rule\":\"custom-payload-flood\",\"count\":1"));
            assertTrue(response.body().contains("\"recentSamples\""));
            assertTrue(response.body().contains("\"remoteAddress\":\"127.0.0.1:50000\""));
            assertTrue(response.body().contains("\"direction\":\"frontend_to_backend\""));
            assertTrue(response.body().contains("\"protocolState\":\"HANDSHAKE\""));
            assertTrue(response.body().contains("\"rawSize\":48"));
            assertTrue(response.body().contains("\"detail\":\"play.example.net\""));
            assertTrue(response.body().indexOf("initial-handshake-malformed-frame")
                    < response.body().indexOf("custom-payload-flood"));
        }
    }

    @Test
    void limitsPacketAnomalyRulesWithoutChangingTotalOrSamples() throws Exception {
        var registry = new InMemoryServerRegistry();
        var metrics = new ProxyMetrics();
        metrics.packetAnomaly("third-rule");
        metrics.packetAnomaly("second-rule");
        metrics.packetAnomaly("second-rule");
        metrics.packetAnomaly("top-rule", "127.0.0.1:50000", "", "frontend_to_backend", "HANDSHAKE", 0, 48, -1, "play.example.net");
        metrics.packetAnomaly("top-rule");
        metrics.packetAnomaly("top-rule");
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, metrics, "", 2)) {
            admin.start();
            var base = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();
            var client = HttpClient.newHttpClient();

            var response = client.send(HttpRequest.newBuilder(URI.create(base + "/packet-anomalies")).GET().build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"total\":6"));
            assertTrue(response.body().contains("\"rule\":\"top-rule\",\"count\":3"));
            assertTrue(response.body().contains("\"rule\":\"second-rule\",\"count\":2"));
            assertTrue(!response.body().contains("\"rule\":\"third-rule\",\"count\":1"));
            assertTrue(response.body().contains("\"recentSamples\""));
            assertTrue(response.body().contains("\"remoteAddress\":\"127.0.0.1:50000\""));
        }
    }

    @Test
    void protectsPacketAnomalyReportWhenBearerTokenIsConfigured() throws Exception {
        var registry = new InMemoryServerRegistry();
        var metrics = new ProxyMetrics();
        metrics.packetAnomaly("protected-rule");
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, metrics, "secret-token")) {
            admin.start();
            var base = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();
            var client = HttpClient.newHttpClient();

            var unauthorized = client.send(HttpRequest.newBuilder(URI.create(base + "/packet-anomalies")).GET().build(), HttpResponse.BodyHandlers.ofString());
            var authorized = client.send(HttpRequest.newBuilder(URI.create(base + "/packet-anomalies"))
                    .header("Authorization", "Bearer secret-token")
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(401, unauthorized.statusCode());
            assertEquals(200, authorized.statusCode());
            assertTrue(authorized.body().contains("protected-rule"));
        }
    }

    @Test
    void exposesPacketTrafficReport() throws Exception {
        var registry = new InMemoryServerRegistry();
        var metrics = new ProxyMetrics();
        metrics.packetTraffic("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, "UNCOMPRESSED", 1, 100, 0);
        metrics.packetTraffic("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, "UNCOMPRESSED", 1, 50, 0);
        metrics.packetTraffic("survival-1", ProxyMetrics.CompressionDirection.BACKEND_TO_FRONTEND, "UNCOMPRESSED", 2, 300, 0);
        metrics.packetTraffic("lobby-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, "UNCOMPRESSED", 3, 25, 0);
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, metrics, "", 2)) {
            admin.start();
            var base = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();
            var client = HttpClient.newHttpClient();

            var response = client.send(HttpRequest.newBuilder(URI.create(base + "/packet-traffic")).GET().build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"totalPackets\":4"));
            assertTrue(response.body().contains("\"totalRawBytes\":475"));
            assertTrue(response.body().contains("\"server\":\"survival-1\",\"direction\":\"backend_to_frontend\",\"protocolState\":\"UNCOMPRESSED\",\"packetId\":2,\"packets\":1,\"rawBytes\":300"));
            assertTrue(response.body().contains("\"server\":\"survival-1\",\"direction\":\"frontend_to_backend\",\"protocolState\":\"UNCOMPRESSED\",\"packetId\":1,\"packets\":2,\"rawBytes\":150"));
            assertTrue(!response.body().contains("\"server\":\"lobby-1\""));
            assertTrue(response.body().indexOf("\"packetId\":2") < response.body().indexOf("\"packetId\":1"));
        }
    }

    @Test
    void exposesCustomPayloadReport() throws Exception {
        var registry = new InMemoryServerRegistry();
        var metrics = new ProxyMetrics();
        metrics.customPayload("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, "FORGE_HANDSHAKE", "fml:handshake", 100, 0);
        metrics.customPayload("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, "FORGE_HANDSHAKE", "fml:handshake", 50, 0);
        metrics.customPayload("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, "FABRIC_HANDSHAKE", "fabric:registry/sync", 300, 64);
        metrics.customPayload("lobby-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, "UNKNOWN", "attacker:random", 25, 0);
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, metrics, "", 2)) {
            admin.start();
            var base = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();
            var client = HttpClient.newHttpClient();

            var response = client.send(HttpRequest.newBuilder(URI.create(base + "/custom-payloads")).GET().build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"totalPackets\":4"));
            assertTrue(response.body().contains("\"totalPayloadBytes\":475"));
            assertTrue(response.body().contains("\"totalCompressedBytes\":64"));
            assertTrue(response.body().contains("\"server\":\"survival-1\",\"direction\":\"frontend_to_backend\",\"kind\":\"FABRIC_HANDSHAKE\",\"channel\":\"fabric:registry/sync\",\"packets\":1,\"payloadBytes\":300,\"compressedBytes\":64,\"maxPayloadBytes\":300,\"maxCompressedBytes\":64"));
            assertTrue(response.body().contains("\"server\":\"survival-1\",\"direction\":\"frontend_to_backend\",\"kind\":\"FORGE_HANDSHAKE\",\"channel\":\"fml:handshake\",\"packets\":2,\"payloadBytes\":150,\"compressedBytes\":0,\"maxPayloadBytes\":100,\"maxCompressedBytes\":0"));
            assertTrue(response.body().contains("\"firstSeen\":"));
            assertTrue(response.body().contains("\"lastSeen\":"));
            assertTrue(!response.body().contains("\"server\":\"lobby-1\""));
            assertTrue(response.body().indexOf("FABRIC_HANDSHAKE") < response.body().indexOf("FORGE_HANDSHAKE"));
        }
    }

    @Test
    void exposesCompressionReportWhenPrometheusIsDisabled() throws Exception {
        var registry = new InMemoryServerRegistry();
        registry.register(new dev.strataproxy.api.server.ServerDescriptor(
                "survival-1",
                new InetSocketAddress("127.0.0.1", 25565),
                java.util.Set.of("survival"),
                java.util.Set.of(),
                new dev.strataproxy.api.server.ProtocolRange(0, Integer.MAX_VALUE, "any"),
                100,
                100,
                120,
                false,
                java.util.Map.of()));
        var metrics = new ProxyMetrics();
        metrics.compressionNegotiated("survival-1", 256);
        metrics.compressionSample("survival-1", 1000, 400, 0);
        metrics.compressionSample("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, 500, 250, 0);
        metrics.compressionDecision("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, "threshold", 1024);
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, metrics, "", 50, false)) {
            admin.start();
            var base = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();
            var client = HttpClient.newHttpClient();

            var metricsResponse = client.send(HttpRequest.newBuilder(URI.create(base + "/metrics")).GET().build(), HttpResponse.BodyHandlers.ofString());
            var report = client.send(HttpRequest.newBuilder(URI.create(base + "/compression-report")).GET().build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(404, metricsResponse.statusCode());
            assertEquals(200, report.statusCode());
            assertTrue(report.body().contains("\"scope\":\"global\",\"direction\":\"all\",\"rawBytes\":1500,\"compressedBytes\":650,\"savedBytes\":850"));
            assertTrue(report.body().contains("\"scope\":\"survival-1\",\"direction\":\"all\",\"rawBytes\":1500,\"compressedBytes\":650,\"savedBytes\":850"));
            assertTrue(report.body().contains("\"scope\":\"survival-1\",\"direction\":\"frontend_to_backend\",\"rawBytes\":500,\"compressedBytes\":250,\"savedBytes\":250"));
            assertTrue(report.body().contains("\"scope\":\"survival-1\",\"direction\":\"frontend_to_backend\",\"action\":\"threshold\",\"threshold\":1024,\"count\":1"));
        }
    }

    @Test
    void exposesServerMetrics() throws Exception {
        var registry = new InMemoryServerRegistry();
        registry.register(new dev.strataproxy.api.server.ServerDescriptor(
                "metrics-1",
                new InetSocketAddress("127.0.0.1", 25565),
                java.util.Set.of("metrics"),
                java.util.Set.of(),
                new dev.strataproxy.api.server.ProtocolRange(0, Integer.MAX_VALUE, "any"),
                100,
                50,
                60,
                false,
                java.util.Map.of()));
        registry.updateHealth("metrics-1", new dev.strataproxy.api.server.ServerHealth(
                dev.strataproxy.api.server.ServerHealthStatus.DEGRADED,
                42,
                0.25d,
                "test",
                java.time.Instant.now()));
        registry.updateLoad("metrics-1", new dev.strataproxy.api.server.ServerLoad(
                12,
                50,
                60,
                1024,
                2048,
                300,
                1.5d));
        var metrics = new ProxyMetrics();
        metrics.frontendToBackendBytes("metrics-1", 1024);
        metrics.backendToFrontendBytes("metrics-1", 2048);
        metrics.compressionSample("metrics-1", 4096, 1024, 500_000_000L);
        metrics.compressionSample("metrics-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, 2048, 512, 0);
        metrics.compressionNegotiated("metrics-1", 512);
        metrics.serverConnectionOpened("metrics-1");
        metrics.playerSessionStarted("Alex", "metrics-1", "127.0.0.1:50001");
        try (var admin = new AdminHttpServer(new InetSocketAddress("127.0.0.1", 0), registry, metrics)) {
            admin.start();
            var base = "http://" + admin.bindAddress().getHostString() + ":" + admin.bindAddress().getPort();
            var client = HttpClient.newHttpClient();

            var response = client.send(HttpRequest.newBuilder(URI.create(base + "/metrics")).GET().build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("strataproxy_server_health{server=\"metrics-1\",status=\"DEGRADED\"} 1"));
            assertTrue(response.body().contains("strataproxy_server_players{server=\"metrics-1\"} 12"));
            assertTrue(response.body().contains("strataproxy_server_packets_per_second{server=\"metrics-1\"} 300"));
            assertTrue(response.body().contains("strataproxy_server_connections_active{server=\"metrics-1\"} 1"));
            assertTrue(response.body().contains("strataproxy_server_connections_routed_total{server=\"metrics-1\"} 1"));
            assertTrue(response.body().contains("strataproxy_server_frontend_to_backend_bytes_total{server=\"metrics-1\"} 1024"));
            assertTrue(response.body().contains("strataproxy_server_backend_to_frontend_bytes_total{server=\"metrics-1\"} 2048"));
            assertTrue(response.body().contains("strataproxy_server_compression_threshold_bytes{server=\"metrics-1\"} 512"));
            assertTrue(response.body().contains("strataproxy_compression_saved_bytes_total{server=\"metrics-1\"} 4608"));
            assertTrue(response.body().contains("strataproxy_compression_ratio{server=\"metrics-1\"} 0.25"));
            assertTrue(response.body().contains("strataproxy_compression_saved_bytes_total{server=\"metrics-1\",direction=\"frontend_to_backend\"} 1536"));
            assertTrue(response.body().contains("strataproxy_compression_saved_bytes_total{server=\"metrics-1\",direction=\"backend_to_frontend\"} 0"));
            assertTrue(response.body().contains("strataproxy_player_session_active{player=\"Alex\",server=\"metrics-1\",remote=\"127.0.0.1:50001\"} 1"));
        }
    }

    private static HttpResponse<String> post(HttpClient client, String uri, String body) throws IOException, InterruptedException {
        return post(client, uri, body, null);
    }

    private static HttpResponse<String> post(HttpClient client, String uri, String body, String bearerToken) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(uri))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> patch(HttpClient client, String uri, String body) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(uri))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> delete(HttpClient client, String uri) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(uri)).DELETE().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static dev.strataproxy.api.server.ServerDescriptor server(
            String name,
            boolean drainMode,
            int softCapacity,
            int hardCapacity) {
        return new dev.strataproxy.api.server.ServerDescriptor(
                name,
                new InetSocketAddress("127.0.0.1", 25565),
                java.util.Set.of(),
                java.util.Set.of(),
                new dev.strataproxy.api.server.ProtocolRange(0, Integer.MAX_VALUE, "any"),
                100,
                softCapacity,
                hardCapacity,
                drainMode,
                java.util.Map.of());
    }

    private static final class FailingRegistryStore implements RegistryStore {
        @Override
        public List<dev.strataproxy.api.server.ServerDescriptor> load() {
            return List.of();
        }

        @Override
        public void save(Collection<dev.strataproxy.api.server.RegisteredServer> servers) throws IOException {
            throw new IOException("persist failed");
        }
    }
}

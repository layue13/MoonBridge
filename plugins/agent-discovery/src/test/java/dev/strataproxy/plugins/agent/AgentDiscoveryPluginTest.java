package dev.strataproxy.plugins.agent;

import dev.strataproxy.api.PlayerView;
import dev.strataproxy.api.Players;
import dev.strataproxy.api.PluginContext;
import dev.strataproxy.api.ServerDefinition;
import dev.strataproxy.api.ServerRegistration;
import dev.strataproxy.api.ServerView;
import dev.strataproxy.api.Servers;
import dev.strataproxy.api.TransferResult;
import dev.strataproxy.api.PlayerIdentity;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class AgentDiscoveryPluginTest {
    private static final byte[] SECRET = "test-secret-with-at-least-32-bytes-long".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Test
    void lateRequestCannotRegisterAfterPluginShutdown() {
        RecordingServers servers = new RecordingServers();
        AgentDiscoveryPlugin plugin = plugin(servers);
        assertEquals(201, plugin.leases.register("agent-a", UUID.randomUUID(), definition("game-1"), 30, 1_000));

        plugin.onDisable();
        assertTrue(servers.definitions.isEmpty());
        assertThrows(IllegalStateException.class, () -> plugin.leases.register(
                "agent-b", UUID.randomUUID(), definition("game-2"), 30, 1_001));
        assertTrue(servers.definitions.isEmpty());
    }

    @Test
    void hmacBindsAgentTimestampNonceAndBodyAndReplayIsRejected() {
        String signature = AgentDiscoveryPlugin.sign(SECRET, "agent-a", "123", "abcdefghijklmnop", "action=register");
        assertEquals(signature, AgentDiscoveryPlugin.sign(SECRET, "agent-a", "123", "abcdefghijklmnop", "action=register"));
        assertNotEquals(signature, AgentDiscoveryPlugin.sign(SECRET, "agent-b", "123", "abcdefghijklmnop", "action=register"));
        assertNotEquals(signature, AgentDiscoveryPlugin.sign(SECRET, "agent-a", "124", "abcdefghijklmnop", "action=register"));
        assertNotEquals(signature, AgentDiscoveryPlugin.sign(SECRET, "agent-a", "123", "abcdefghijklmnop", "action=unregister"));

        AgentDiscoveryPlugin plugin = plugin(new RecordingServers());
        plugin.leases.acceptNonce("agent-a", "abcdefghijklmnop", 1000);
        assertThrows(RuntimeException.class, () -> plugin.leases.acceptNonce("agent-a", "abcdefghijklmnop", 1000));
        plugin.onDisable();
    }

    @Test
    void expiredGenerationIsFencedAndNewGenerationCanClaimAfterExpiry() {
        RecordingServers servers = new RecordingServers();
        AgentDiscoveryPlugin plugin = plugin(servers);
        UUID first = UUID.randomUUID();
        ServerDefinition definition = definition("game-1");
        assertEquals(201, plugin.leases.register("agent-a", first, definition, 5, 1_000));
        plugin.leases.expire(6_001);
        assertEquals(1, servers.unregisterCount);
        assertThrows(RuntimeException.class, () -> plugin.leases.register("agent-a", first, definition, 5, 6_002));

        UUID restarted = UUID.randomUUID();
        assertEquals(201, plugin.leases.register("agent-a", restarted, definition, 5, 6_003));
        assertThrows(RuntimeException.class, () -> plugin.leases.register("agent-a", first, definition, 5, 6_004));
        plugin.onDisable();
    }

    @Test
    void activeGenerationCannotBeReplacedAndHeartbeatExtendsLease() {
        RecordingServers servers = new RecordingServers();
        AgentDiscoveryPlugin plugin = plugin(servers);
        UUID generation = UUID.randomUUID();
        assertEquals(201, plugin.leases.register("agent-a", generation, definition("game-1"), 5, 10_000));
        assertThrows(RuntimeException.class, () -> plugin.leases.register("agent-a", UUID.randomUUID(), definition("game-1"), 5, 11_000));
        assertEquals(200, plugin.leases.register("agent-a", generation, definition("game-1"), 5, 14_000));
        plugin.leases.expire(18_500);
        assertEquals(0, servers.unregisterCount);
        plugin.leases.expire(19_001);
        assertEquals(1, servers.unregisterCount);
        plugin.onDisable();
    }

    @Test
    void moreThanEightExpiredGenerationsCanRotateAndTombstonesArePruned() {
        RecordingServers servers = new RecordingServers();
        AgentDiscoveryPlugin plugin = plugin(servers);
        long now = 100_000;
        UUID firstGeneration = null;
        UUID previousGeneration = null;
        for (int restart = 0; restart < 9; restart++) {
            UUID generation = UUID.randomUUID();
            if (firstGeneration == null) firstGeneration = generation;
            assertEquals(201, plugin.leases.register("agent-restart", generation, definition("game-restart"), 5, now));
            plugin.leases.expire(now + 5_001);
            if (previousGeneration != null) {
                UUID staleGeneration = previousGeneration;
                long staleAttemptTime = now + 5_002;
                assertThrows(RuntimeException.class, () -> plugin.leases.register(
                        "agent-restart", staleGeneration, definition("game-restart"), 5, staleAttemptTime));
            }
            previousGeneration = generation;
            now += 5_002;
        }
        assertEquals(9, servers.unregisterCount);

        long afterRetention = now + 120_001;
        plugin.leases.expire(afterRetention);
        assertEquals(201, plugin.leases.register("agent-restart", firstGeneration,
                definition("game-restart"), 5, afterRetention + 1));
        plugin.onDisable();
    }

    @Test
    void listenerRejectsBadHmacAndExactReplayAndCapsRequestBody() throws Exception {
        RecordingServers servers = new RecordingServers();
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        AgentDiscoveryPlugin plugin = plugin(servers, port);
        plugin.onEnable();
        try {
            String body = "action=register&generation=" + UUID.randomUUID()
                    + "&name=game-http&address=tcp%3A%2F%2F127.0.0.1%3A25565&capacity=10&leaseSeconds=30";
            String agent = "agent-http";
            String timestamp = Long.toString(System.currentTimeMillis() / 1000);
            String nonce = "abcdefghijklmnopqrstuvwx";
            URI endpoint = URI.create("http://127.0.0.1:" + port + AgentDiscoveryPlugin.PATH);
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpResponse<String> badSignature = post(client, endpoint, agent, timestamp, nonce, "0".repeat(64), body);
            assertEquals(401, badSignature.statusCode());

            String signature = AgentDiscoveryPlugin.sign(SECRET, agent, timestamp, nonce, body);
            HttpResponse<String> registered = post(client, endpoint, agent, timestamp, nonce, signature, body);
            assertEquals(201, registered.statusCode());
            HttpResponse<String> replay = post(client, endpoint, agent, timestamp, nonce, signature, body);
            assertEquals(401, replay.statusCode());

            String largeBody = "x".repeat(300);
            HttpResponse<String> oversized = post(client, endpoint, agent, timestamp, "zyxwvutsrqponmlkjihgfedc", "0".repeat(64), largeBody);
            assertEquals(413, oversized.statusCode());
        } finally {
            plugin.onDisable();
        }
    }

    private static HttpResponse<String> post(HttpClient client, URI endpoint, String agent, String timestamp,
                                              String nonce, String signature, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                .header("X-Agent-Id", agent).header("X-Timestamp", timestamp)
                .header("X-Nonce", nonce).header("X-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static AgentDiscoveryPlugin plugin(RecordingServers servers) {
        return plugin(servers, 28080);
    }

    private static AgentDiscoveryPlugin plugin(RecordingServers servers, int port) {
        AgentDiscoveryPlugin plugin = new AgentDiscoveryPlugin();
        plugin.onLoad(new PluginContext() {
            @Override public Players players() {
                return new Players() {
                    @Override public Optional<PlayerView> find(PlayerIdentity identity) { return Optional.empty(); }
                    @Override public List<PlayerView> online() { return List.of(); }
                    @Override public java.util.concurrent.CompletionStage<TransferResult> transfer(PlayerIdentity identity, String backendName) {
                        return CompletableFuture.failedFuture(new UnsupportedOperationException());
                    }
                };
            }
            @Override public Servers servers() { return servers; }
            @Override public org.slf4j.Logger logger() { return org.slf4j.LoggerFactory.getLogger("agent-test"); }
            @Override public Map<String, String> settings() {
                return Map.of("secret", new String(SECRET, StandardCharsets.UTF_8), "port", Integer.toString(port),
                        "maxBodyBytes", "256");
            }
        });
        return plugin;
    }

    private static ServerDefinition definition(String name) {
        return new ServerDefinition(name, URI.create("tcp://127.0.0.1:25565"), Map.of(), 50, Map.of());
    }

    private static final class RecordingServers implements Servers {
        private final Map<String, ServerDefinition> definitions = new HashMap<>();
        private int unregisterCount;
        @Override public Optional<ServerView> find(String backendName) { return Optional.empty(); }
        @Override public List<ServerView> all() { return List.of(); }
        @Override public ServerRegistration register(ServerDefinition definition) {
            definitions.put(definition.name(), definition);
            return new ServerRegistration() {
                private boolean active = true;
                @Override public void update(ServerDefinition replacement) {
                    if (!active) throw new IllegalStateException("stale");
                    definitions.put(replacement.name(), replacement);
                }
                @Override public void unregister() {
                    if (!active) throw new IllegalStateException("stale");
                    active = false;
                    definitions.remove(definition.name());
                    unregisterCount++;
                }
            };
        }
    }
}

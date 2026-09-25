package dev.strataproxy.plugins.agent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.strataproxy.api.Plugin;
import dev.strataproxy.api.PluginContext;
import dev.strataproxy.api.ServerDefinition;
import dev.strataproxy.api.ServerRegistration;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Optional authenticated HTTP registration endpoint for external instance agents. */
public final class AgentDiscoveryPlugin implements Plugin {
    static final String PATH = "/registration";
    private static final int ABSOLUTE_BODY_LIMIT = 8192;
    private static final int MAX_NONCES = 4096;
    private static final int SIGNATURE_WINDOW_SECONDS = 60;
    private static final int MAX_RETIRED_GENERATIONS = 65_536;
    private PluginContext context;
    private Configuration configuration;
    private HttpServer server;
    private ThreadPoolExecutor requests;
    private ScheduledExecutorService reaper;
    final LeaseRegistry leases = new LeaseRegistry();

    @Override
    public synchronized void onLoad(PluginContext pluginContext) {
        if (context != null) throw new IllegalStateException("Agent discovery plugin is already loaded");
        context = Objects.requireNonNull(pluginContext, "pluginContext");
        configuration = Configuration.from(context.settings());
    }

    @Override
    public synchronized void onEnable() {
        if (context == null) throw new IllegalStateException("onLoad must complete before onEnable");
        if (server != null) return;
        try {
            requests = new ThreadPoolExecutor(configuration.concurrency(), configuration.concurrency(), 0,
                    TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(configuration.concurrency() * 8), task -> {
                Thread thread = new Thread(task, "strataproxy-agent-request");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
            server = HttpServer.create(new InetSocketAddress(configuration.host(), configuration.port()), 32);
            server.setExecutor(requests);
            server.createContext(PATH, this::handle);
            reaper = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "strataproxy-agent-lease-reaper");
                thread.setDaemon(true);
                return thread;
            });
            reaper.scheduleWithFixedDelay(() -> leases.expire(System.currentTimeMillis()), 1, 1, TimeUnit.SECONDS);
            server.start();
            context.logger().info("Agent discovery listening on {}:{}", configuration.host(), server.getAddress().getPort());
        } catch (IOException failure) {
            stopServices();
            throw new IllegalStateException("Could not start agent discovery listener", failure);
        }
    }

    @Override
    public synchronized void onDisable() {
        leases.close();
        stopServices();
    }

    private synchronized void stopServices() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (requests != null) {
            requests.shutdownNow();
            requests = null;
        }
        if (reaper != null) {
            reaper.shutdownNow();
            reaper = null;
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        int status = 400;
        String reply = "bad request";
        try {
            if (!PATH.equals(exchange.getRequestURI().getPath()) || !"POST".equals(exchange.getRequestMethod())) {
                status = 404;
                reply = "not found";
            } else {
                String length = exchange.getRequestHeaders().getFirst("Content-Length");
                if (length != null && parseDecimal(length, -1) > configuration.maxBodyBytes()) {
                    status = 413;
                    reply = "request too large";
                } else {
                    byte[] body = exchange.getRequestBody().readNBytes(configuration.maxBodyBytes() + 1);
                    if (body.length > configuration.maxBodyBytes()) {
                        status = 413;
                        reply = "request too large";
                    } else if (!authenticate(exchange, body)) {
                        status = 401;
                        reply = "authentication failed";
                    } else {
                        Map<String, String> values = parseForm(body);
                        String agentId = exchange.getRequestHeaders().getFirst("X-Agent-Id");
                        if (!validAgentId(agentId)) throw new BadRequest("invalid agent id");
                        int result = apply(agentId, values);
                        status = result;
                        reply = result == 204 ? "" : result == 201 ? "registered" : "renewed";
                    }
                }
            }
        } catch (Replay replay) {
            status = 401;
            reply = "authentication failed";
        } catch (Conflict conflict) {
            status = 409;
            reply = "registration conflict";
        } catch (BadRequest invalid) {
            status = 400;
            reply = "bad request";
        } catch (RuntimeException failure) {
            context.logger().warn("Agent registration request failed", failure);
            status = 503;
            reply = "temporarily unavailable";
        } finally {
            try {
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
                if (status == 204) exchange.sendResponseHeaders(status, -1);
                else {
                    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            } catch (IOException ignored) {
                // The client may have disconnected after the state change was committed.
            }
            exchange.close();
        }
    }

    private boolean authenticate(HttpExchange exchange, byte[] body) {
        String agentId = exchange.getRequestHeaders().getFirst("X-Agent-Id");
        String timestampText = exchange.getRequestHeaders().getFirst("X-Timestamp");
        String nonce = exchange.getRequestHeaders().getFirst("X-Nonce");
        String signature = exchange.getRequestHeaders().getFirst("X-Signature");
        long timestamp = parseDecimal(timestampText, Long.MIN_VALUE);
        long now = System.currentTimeMillis() / 1000;
        if (!validAgentId(agentId) || timestamp == Long.MIN_VALUE
                || timestamp < now - SIGNATURE_WINDOW_SECONDS || timestamp > now + SIGNATURE_WINDOW_SECONDS
                || nonce == null || !nonce.matches("[A-Za-z0-9_-]{16,64}")
                || signature == null || !signature.matches("[A-Fa-f0-9]{64}")) return false;
        String canonical = "POST\n" + PATH + "\n" + timestamp + "\n" + nonce + "\n" + agentId + "\n"
                + new String(body, StandardCharsets.UTF_8);
        byte[] expected = hmac(configuration.secret(), canonical.getBytes(StandardCharsets.UTF_8));
        byte[] supplied;
        try { supplied = java.util.HexFormat.of().parseHex(signature); }
        catch (IllegalArgumentException invalid) { return false; }
        if (!MessageDigest.isEqual(expected, supplied)) return false;
        leases.acceptNonce(agentId, nonce, now);
        return true;
    }

    private int apply(String agentId, Map<String, String> input) {
        String action = required(input, "action");
        UUID generation;
        try { generation = UUID.fromString(required(input, "generation")); }
        catch (IllegalArgumentException invalid) { throw new BadRequest("invalid generation"); }
        if (action.equals("unregister")) {
            requireKeys(input, Set.of("action", "generation"));
            leases.unregister(agentId, generation);
            return 204;
        }
        if (!action.equals("register")) throw new BadRequest("invalid action");
        requireKeys(input, Set.of("action", "generation", "name", "address", "capacity", "leaseSeconds"));
        String name = required(input, "name");
        URI address;
        try { address = URI.create(required(input, "address")); }
        catch (IllegalArgumentException invalid) { throw new BadRequest("invalid address"); }
        int capacity = integer(input, "capacity", 0, Integer.MAX_VALUE);
        int leaseSeconds = integer(input, "leaseSeconds", 5, configuration.maxLeaseSeconds());
        ServerDefinition definition;
        try {
            definition = new ServerDefinition(name, address, Map.of("discovery", "agent"), capacity,
                    Map.of("agent.id", agentId, "agent.generation", generation.toString()));
        } catch (IllegalArgumentException invalid) { throw new BadRequest("invalid server definition"); }
        return leases.register(agentId, generation, definition, leaseSeconds, System.currentTimeMillis());
    }

    private static Map<String, String> parseForm(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(text.getBytes(StandardCharsets.UTF_8), bytes)) throw new BadRequest("invalid UTF-8");
        Map<String, String> values = new LinkedHashMap<>();
        if (text.isEmpty()) throw new BadRequest("empty body");
        for (String part : text.split("&", -1)) {
            int equals = part.indexOf('=');
            if (equals < 1) throw new BadRequest("invalid form");
            String key = decode(part.substring(0, equals));
            String value = decode(part.substring(equals + 1));
            if (values.putIfAbsent(key, value) != null) throw new BadRequest("duplicate field");
        }
        return values;
    }

    private static String decode(String value) {
        try { return URLDecoder.decode(value, StandardCharsets.UTF_8); }
        catch (IllegalArgumentException invalid) { throw new BadRequest("invalid encoding"); }
    }

    private static void requireKeys(Map<String, String> input, Set<String> keys) {
        if (!input.keySet().equals(keys)) throw new BadRequest("unexpected fields");
    }
    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) throw new BadRequest("missing field");
        return value;
    }
    private static int integer(Map<String, String> values, String key, int min, int max) {
        long parsed = parseDecimal(values.get(key), Long.MIN_VALUE);
        if (parsed < min || parsed > max) throw new BadRequest("invalid integer");
        return (int) parsed;
    }
    private static long parseDecimal(String value, long fallback) {
        if (value == null || !value.matches("[0-9]{1,19}")) return fallback;
        try { return Long.parseLong(value); }
        catch (NumberFormatException invalid) { return fallback; }
    }
    private static boolean validAgentId(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    }
    private static byte[] hmac(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (java.security.GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }

    static String sign(byte[] secret, String agentId, String timestamp, String nonce, String body) {
        String canonical = "POST\n" + PATH + "\n" + timestamp + "\n" + nonce + "\n" + agentId + "\n" + body;
        return java.util.HexFormat.of().formatHex(hmac(secret, canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private record Configuration(String host, int port, byte[] secret, int concurrency, int maxBodyBytes,
                                 int maxLeaseSeconds, int maxInstances) {
        static Configuration from(Map<String, String> values) {
            String secret = values.get("secret");
            if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32)
                throw new IllegalArgumentException("setting 'secret' must contain at least 32 UTF-8 bytes");
            String host = values.getOrDefault("host", "127.0.0.1").trim();
            if (host.isEmpty()) throw new IllegalArgumentException("setting 'host' must not be blank");
            int port = setting(values, "port", 28080, 1, 65535);
            int concurrency = setting(values, "concurrency", 4, 1, 64);
            int maxBytes = setting(values, "maxBodyBytes", 4096, 256, ABSOLUTE_BODY_LIMIT);
            int lease = setting(values, "maxLeaseSeconds", 60, 5, 3600);
            int instances = setting(values, "maxInstances", 128, 1, 4096);
            return new Configuration(host, port, secret.getBytes(StandardCharsets.UTF_8), concurrency, maxBytes, lease, instances);
        }
        private static int setting(Map<String, String> values, String key, int fallback, int min, int max) {
            String value = values.get(key);
            if (value == null) return fallback;
            long parsed = parseDecimal(value, Long.MIN_VALUE);
            if (parsed < min || parsed > max) throw new IllegalArgumentException("setting '" + key + "' must be " + min + ".." + max);
            return (int) parsed;
        }
    }

    /** Synchronized lease table; generation tombstones fence delayed requests after expiry/unregister. */
    final class LeaseRegistry {
        private final Map<String, Lease> active = new LinkedHashMap<>();
        private final Map<String, Map<UUID, Long>> retiredGenerations = new LinkedHashMap<>();
        private final Map<String, String> serverOwners = new LinkedHashMap<>();
        private final LinkedHashMap<String, Long> nonces = new LinkedHashMap<>();
        private int retiredGenerationCount;
        private boolean closed;

        synchronized void acceptNonce(String agent, String nonce, long nowSeconds) {
            requireOpen();
            nonces.entrySet().removeIf(entry -> entry.getValue() < nowSeconds - SIGNATURE_WINDOW_SECONDS);
            String key = agent + ":" + nonce;
            if (nonces.containsKey(key)) throw new Replay();
            if (nonces.size() >= MAX_NONCES) throw new Replay();
            nonces.put(key, nowSeconds);
        }

        synchronized int register(String agent, UUID generation, ServerDefinition definition, int leaseSeconds, long now) {
            requireOpen();
            expire(now);
            Lease old = active.get(agent);
            if (old != null && !old.generation.equals(generation)) throw new Conflict();
            if (old == null) {
                if (retiredGenerations.getOrDefault(agent, Map.of()).containsKey(generation)) throw new Conflict();
                if (active.size() >= configuration.maxInstances()) throw new Conflict();
                if (retiredGenerationCount >= MAX_RETIRED_GENERATIONS) throw new Conflict();
                String owner = serverOwners.get(definition.name());
                if (owner != null && !owner.equals(agent)) throw new Conflict();
                ServerRegistration registration = context.servers().register(definition);
                serverOwners.put(definition.name(), agent);
                active.put(agent, new Lease(generation, definition.name(), definition, registration, now + leaseSeconds * 1000L));
                return 201;
            }
            if (!old.name.equals(definition.name())) {
                if (serverOwners.containsKey(definition.name())) throw new Conflict();
                ServerRegistration replacement = context.servers().register(definition);
                try {
                    old.registration.unregister();
                } catch (RuntimeException failedRemoval) {
                    try { replacement.unregister(); }
                    catch (RuntimeException rollbackFailure) { failedRemoval.addSuppressed(rollbackFailure); }
                    throw failedRemoval;
                }
                serverOwners.remove(old.name);
                serverOwners.put(definition.name(), agent);
                active.put(agent, new Lease(generation, definition.name(), definition, replacement, now + leaseSeconds * 1000L));
            } else {
                old.registration.update(definition);
                active.put(agent, new Lease(generation, old.name, definition, old.registration, now + leaseSeconds * 1000L));
            }
            return 200;
        }

        synchronized void unregister(String agent, UUID generation) {
            requireOpen();
            Lease lease = active.get(agent);
            if (lease == null) {
                if (!retiredGenerations.getOrDefault(agent, Map.of()).containsKey(generation)) throw new Conflict();
                return;
            }
            if (!lease.generation.equals(generation)) throw new Conflict();
            lease.registration.unregister();
            active.remove(agent);
            serverOwners.remove(lease.name, agent);
            retire(agent, generation, System.currentTimeMillis());
        }

        synchronized void expire(long now) {
            if (closed) return;
            for (String id : new ArrayList<>(active.keySet())) {
                Lease lease = active.get(id);
                if (lease != null && lease.expiresAtMillis <= now) {
                    try {
                        lease.registration.unregister();
                        active.remove(id);
                        serverOwners.remove(lease.name, id);
                        retire(id, lease.generation, now);
                        context.logger().info("Expired agent registration {}", id);
                    } catch (RuntimeException failure) {
                        context.logger().warn("Could not expire agent registration {}; retrying", id, failure);
                    }
                }
            }
            pruneRetiredGenerations(now);
        }

        private void retire(String agent, UUID generation, long now) {
            Map<UUID, Long> generations = retiredGenerations.computeIfAbsent(agent, ignored -> new LinkedHashMap<>());
            if (!generations.containsKey(generation)) {
                generations.put(generation, now + configuration.maxLeaseSeconds() * 1000L
                        + SIGNATURE_WINDOW_SECONDS * 1000L);
                retiredGenerationCount++;
            }
        }

        private void pruneRetiredGenerations(long now) {
            for (String agent : new ArrayList<>(retiredGenerations.keySet())) {
                Map<UUID, Long> generations = retiredGenerations.get(agent);
                generations.entrySet().removeIf(entry -> {
                    if (entry.getValue() > now) return false;
                    retiredGenerationCount--;
                    return true;
                });
                if (generations.isEmpty()) retiredGenerations.remove(agent);
            }
        }

        synchronized void close() {
            if (closed) return;
            closed = true;
            for (Map.Entry<String, Lease> entry : new ArrayList<>(active.entrySet())) {
                try { entry.getValue().registration.unregister(); }
                catch (RuntimeException failure) { context.logger().warn("Could not remove agent registration {}", entry.getKey(), failure); }
            }
            active.clear();
            serverOwners.clear();
        }

        private void requireOpen() {
            if (closed) throw new IllegalStateException("agent discovery is closed");
        }
    }
    private record Lease(UUID generation, String name, ServerDefinition definition, ServerRegistration registration,
                         long expiresAtMillis) { }
    private static class BadRequest extends RuntimeException { BadRequest(String message) { super(message); } }
    private static class Conflict extends RuntimeException { }
    private static class Replay extends RuntimeException { }
}

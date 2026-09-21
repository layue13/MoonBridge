package dev.strataproxy.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.strataproxy.bootstrap.ProxyConfig;
import dev.strataproxy.plugin.service.ServerMutationResult;
import dev.strataproxy.plugin.service.ServerPersistence;
import dev.strataproxy.plugin.service.ServerProtocolRange;
import dev.strataproxy.plugin.service.ServerRegistration;
import dev.strataproxy.plugin.service.ServerRemoval;
import dev.strataproxy.plugin.service.ServerService;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Narrow authenticated endpoint for backend agents. It deliberately exposes only
 * backend registration, lease renewal and removal; it is not an administration API.
 */
public final class BackendAgentServer implements AutoCloseable {
    private static final int MAX_LINE_LENGTH = 64 * 1024;
    private static final long MAX_CLOCK_SKEW_MILLIS = 60_000L;

    private final ProxyConfig.BackendAgentConfig config;
    private final ServerService servers;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Long> nonces = new ConcurrentHashMap<>();
    private final Map<String, Lease> leases = new ConcurrentHashMap<>();
    private final ExecutorService connections = Executors.newCachedThreadPool(runnable -> new Thread(runnable, "strataproxy-backend-agent"));
    private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, "strataproxy-backend-agent-lease"));
    private volatile ServerSocket socket;
    private volatile boolean closed;

    public BackendAgentServer(ProxyConfig.BackendAgentConfig config, ServerService servers) {
        this.config = config;
        this.servers = servers;
    }

    /** Starts the endpoint and the lease sweeper. */
    public void start() throws IOException {
        socket = new ServerSocket();
        socket.bind(config.bindAddress());
        connections.execute(this::acceptLoop);
        var delay = Math.max(1_000L, config.heartbeatTimeout().toMillis() / 2L);
        maintenance.scheduleWithFixedDelay(this::expireLeases, delay, delay, TimeUnit.MILLISECONDS);
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                var connection = socket.accept();
                connections.execute(() -> handle(connection));
            } catch (IOException exception) {
                if (!closed) {
                    // The next accept is not useful after a listener failure.
                    close();
                }
            }
        }
    }

    private void handle(Socket connection) {
        try (connection;
             var reader = new BufferedReader(new java.io.InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
             var writer = new BufferedWriter(new java.io.OutputStreamWriter(connection.getOutputStream(), StandardCharsets.UTF_8))) {
            connection.setSoTimeout(5_000);
            var line = reader.readLine();
            var response = line == null || line.length() > MAX_LINE_LENGTH
                    ? Response.failure("invalid_request", "request is missing or too large")
                    : process(line);
            writer.write(mapper.writeValueAsString(response));
            writer.newLine();
            writer.flush();
        } catch (IOException ignored) {
            // A disconnected backend agent has no state to clean up here; lease expiry handles it.
        }
    }

    private Response process(String line) {
        try {
            var envelope = mapper.readValue(line, Envelope.class);
            if (envelope == null || envelope.nonce == null || envelope.payload == null || envelope.signature == null) {
                return Response.failure("invalid_request", "missing authentication fields");
            }
            var now = System.currentTimeMillis();
            if (Math.abs(now - envelope.timestamp) > MAX_CLOCK_SKEW_MILLIS) {
                return Response.failure("unauthorized", "request timestamp is outside the allowed clock skew");
            }
            if (nonces.putIfAbsent(envelope.nonce, now) != null) {
                return Response.failure("unauthorized", "request nonce was already used");
            }
            var signed = envelope.timestamp + "\n" + envelope.nonce + "\n" + envelope.payload;
            var expected = hmac(signed);
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), envelope.signature.getBytes(StandardCharsets.US_ASCII))) {
                return Response.failure("unauthorized", "invalid request signature");
            }
            var command = mapper.readValue(Base64.getUrlDecoder().decode(envelope.payload), Command.class);
            if (command == null || command.operation == null || command.name == null || command.name.isBlank()) {
                return Response.failure("invalid_request", "operation and backend name are required");
            }
            return switch (command.operation) {
                case "register" -> register(command);
                case "heartbeat" -> heartbeat(command);
                case "unregister" -> unregister(command);
                default -> Response.failure("invalid_request", "unsupported operation");
            };
        } catch (IllegalArgumentException exception) {
            return Response.failure("invalid_request", exception.getMessage());
        } catch (Exception exception) {
            return Response.failure("request_failed", rootMessage(exception));
        } finally {
            var cutoff = System.currentTimeMillis() - MAX_CLOCK_SKEW_MILLIS;
            nonces.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        }
    }

    private Response register(Command command) throws Exception {
        if (command.host == null || command.host.isBlank() || command.port < 1 || command.port > 65535) {
            return Response.failure("invalid_request", "backend host and port are required");
        }
        var persistence = command.persistent ? ServerPersistence.PERSISTENT : ServerPersistence.EPHEMERAL;
        var registration = new ServerRegistration(
                command.name,
                new java.net.InetSocketAddress(command.host, command.port),
                command.tags == null ? Set.of() : Set.copyOf(command.tags),
                command.capabilities == null ? Set.of() : Set.copyOf(command.capabilities),
                command.minProtocol == null && command.maxProtocol == null
                        ? ServerProtocolRange.any()
                        : new ServerProtocolRange(command.minProtocol == null ? 0 : command.minProtocol,
                                command.maxProtocol == null ? Integer.MAX_VALUE : command.maxProtocol,
                                command.protocolName == null ? "configured" : command.protocolName),
                command.weight == null ? 100 : command.weight,
                command.softCapacity == null ? 0 : command.softCapacity,
                command.hardCapacity == null ? 0 : command.hardCapacity,
                command.drainMode,
                command.metadata == null ? Map.of() : Map.copyOf(command.metadata),
                persistence);
        var result = servers.register(registration).toCompletableFuture().get(5, TimeUnit.SECONDS);
        if (result.success()) {
            leases.put(command.name.trim(), new Lease(System.currentTimeMillis(), persistence));
        }
        return response(result);
    }

    private Response heartbeat(Command command) {
        var lease = leases.get(command.name.trim());
        if (lease == null) {
            return Response.failure("not_registered", "backend must register before sending heartbeats");
        }
        leases.put(command.name.trim(), new Lease(System.currentTimeMillis(), lease.persistence));
        return Response.success("heartbeat", "");
    }

    private Response unregister(Command command) throws Exception {
        var lease = leases.get(command.name.trim());
        var persistence = lease == null ? (command.persistent ? ServerPersistence.PERSISTENT : ServerPersistence.EPHEMERAL) : lease.persistence;
        var result = servers.unregister(command.name, new ServerRemoval(true, false, Duration.ZERO, persistence))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        if (result.success()) {
            leases.remove(command.name.trim());
        }
        return response(result);
    }

    private void expireLeases() {
        var cutoff = System.currentTimeMillis() - config.heartbeatTimeout().toMillis();
        leases.forEach((name, lease) -> {
            if (lease.lastSeen < cutoff && leases.remove(name, lease)) {
                servers.unregister(name, new ServerRemoval(true, false, Duration.ZERO, lease.persistence));
            }
        });
    }

    private String hmac(String value) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(config.sharedSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static Response response(ServerMutationResult result) {
        return new Response(result.success(), result.outcome(), result.message());
    }

    private static String rootMessage(Throwable exception) {
        var cause = exception;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    @Override
    public void close() {
        closed = true;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        maintenance.shutdownNow();
        connections.shutdownNow();
    }

    private record Lease(long lastSeen, ServerPersistence persistence) { }

    public static final class Envelope {
        public long timestamp;
        public String nonce;
        public String payload;
        public String signature;
    }

    public static final class Command {
        public String operation;
        public String name;
        public String host;
        public int port;
        public Set<String> tags;
        public Set<String> capabilities;
        public Integer minProtocol;
        public Integer maxProtocol;
        public String protocolName;
        public Integer weight;
        public Integer softCapacity;
        public Integer hardCapacity;
        public boolean drainMode;
        public Map<String, String> metadata;
        public boolean persistent;
    }

    public record Response(boolean success, String outcome, String message) {
        static Response success(String outcome, String message) { return new Response(true, outcome, message); }
        static Response failure(String outcome, String message) { return new Response(false, outcome, message == null ? "" : message); }
    }
}

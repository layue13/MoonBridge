package dev.strataproxy.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.strataproxy.backend.internal.ChannelFrame;
import dev.strataproxy.backend.internal.ChannelWire;
import dev.strataproxy.app.ProxyConfig;
import dev.strataproxy.plugin.service.ServerMutationResult;
import dev.strataproxy.plugin.service.ServerPersistence;
import dev.strataproxy.plugin.service.ServerProtocolRange;
import dev.strataproxy.plugin.service.ServerRegistration;
import dev.strataproxy.plugin.service.ServerRemoval;
import dev.strataproxy.plugin.service.ServerService;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Narrow authenticated endpoint for backend agents. It deliberately exposes only
 * backend registration, lease renewal and removal; it is not an administration API.
 */
public final class BackendAgentServer implements AutoCloseable {
    private static final int MAX_LINE_LENGTH = 64 * 1024;
    private static final long MAX_CLOCK_SKEW_MILLIS = 60_000L;
    private static final int LEASE_LOCK_STRIPES = 64;

    private final ProxyConfig.BackendAgentConfig config;
    private final ServerService servers;
    private final BackendMessageBroker broker;
    private final boolean ownsBroker;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Long> nonces = new ConcurrentHashMap<>();
    private final Map<String, Lease> leases = new ConcurrentHashMap<>();
    private final Set<AgentStream> streams = ConcurrentHashMap.newKeySet();
    private final ReentrantLock[] leaseLocks = new ReentrantLock[LEASE_LOCK_STRIPES];
    private final ExecutorService connections;
    private final Semaphore streamSlots;
    private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, "strataproxy-backend-agent-lease"));
    private volatile ServerSocket socket;
    private volatile boolean closed;

    public BackendAgentServer(ProxyConfig.BackendAgentConfig config, ServerService servers) {
        this(config, servers, new BackendMessageBroker(), true);
    }

    public BackendAgentServer(ProxyConfig.BackendAgentConfig config, ServerService servers, BackendMessageBroker broker) {
        this(config, servers, broker, false);
    }

    private BackendAgentServer(ProxyConfig.BackendAgentConfig config, ServerService servers, BackendMessageBroker broker, boolean ownsBroker) {
        this.config = config;
        this.servers = servers;
        this.broker = broker;
        this.ownsBroker = ownsBroker;
        this.streamSlots = new Semaphore(config.maxConnections());
        for (var index = 0; index < leaseLocks.length; index++) {
            leaseLocks[index] = new ReentrantLock();
        }
        this.connections = new ThreadPoolExecutor(
                config.maxConnections(), config.maxConnections(), 0L, TimeUnit.MILLISECONDS,
                config.maxQueuedConnections() == 0 ? new SynchronousQueue<>() : new ArrayBlockingQueue<>(config.maxQueuedConnections()),
                runnable -> new Thread(runnable, "strataproxy-backend-agent"),
                (runnable, executor) -> {
                    if (runnable instanceof ConnectionTask task) task.close();
                });
    }

    /** Starts the endpoint and the lease sweeper. */
    public void start() throws IOException {
        socket = new ServerSocket();
        socket.bind(config.bindAddress());
        var acceptor = new Thread(this::acceptLoop, "strataproxy-backend-agent-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        var delay = Math.max(1_000L, config.heartbeatTimeout().toMillis() / 2L);
        maintenance.scheduleWithFixedDelay(this::expireLeases, delay, delay, TimeUnit.MILLISECONDS);
        maintenance.scheduleWithFixedDelay(broker::retryPending, 5, 5, TimeUnit.SECONDS);
    }

    /** Returns the actual listener address after start, including an ephemeral port when requested. */
    public java.net.SocketAddress localAddress() {
        return socket == null ? null : socket.getLocalSocketAddress();
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                var connection = socket.accept();
                connections.execute(new ConnectionTask(connection));
            } catch (IOException exception) {
                if (!closed) {
                    // The next accept is not useful after a listener failure.
                    close();
                }
            }
        }
    }

    private final class ConnectionTask implements Runnable {
        private final Socket connection;

        private ConnectionTask(Socket connection) { this.connection = connection; }
        @Override public void run() { handle(connection); }
        private void close() { try { connection.close(); } catch (IOException ignored) { } }
    }

    private void handle(Socket connection) {
        var stream = new StreamHolder();
        try {
            var writer = new BufferedWriter(new java.io.OutputStreamWriter(connection.getOutputStream(), StandardCharsets.UTF_8));
            connection.setSoTimeout(5_000);
            var line = readBoundedLine(connection.getInputStream());
            var response = line == null
                    ? Response.failure("invalid_request", "request is missing or too large")
                    : process(line, connection, stream);
            writer.write(mapper.writeValueAsString(response));
            writer.newLine();
            writer.flush();
            if (stream.session != null && response.success()) {
                connection.setSoTimeout(0);
                stream.session.running.set(true);
                Thread.ofVirtual().name("strataproxy-agent-stream-" + stream.session.name).start(stream.session::run);
                return;
            }
        } catch (IOException ignored) {
            // A disconnected backend agent has no state to clean up here; lease expiry handles it.
        } finally {
            if (stream.session != null && !stream.session.running.get()) stream.session.close();
            if (stream.session == null || !stream.session.running.get()) {
                try { connection.close(); } catch (IOException ignored) { }
            }
        }
    }

    private Response process(String line, Socket connection, StreamHolder stream) {
        try {
            var envelope = mapper.readValue(line, Envelope.class);
            if (envelope == null || envelope.agentId == null || envelope.nonce == null || envelope.payload == null || envelope.signature == null) {
                return Response.failure("invalid_request", "missing authentication fields");
            }
            var agentId = envelope.agentId.trim();
            if (agentId.isEmpty() || agentId.length() > 128 || envelope.nonce.length() > 128
                    || envelope.payload.length() > MAX_LINE_LENGTH || envelope.signature.length() > 128
                    || config.sharedSecret().isBlank()) {
                return Response.failure("unauthorized", "backend agent authentication is not configured or malformed");
            }
            var now = System.currentTimeMillis();
            if (envelope.timestamp < now - MAX_CLOCK_SKEW_MILLIS
                    || envelope.timestamp > now + MAX_CLOCK_SKEW_MILLIS) {
                return Response.failure("unauthorized", "request timestamp is outside the allowed clock skew");
            }
            var signed = envelope.agentId + "\n" + envelope.timestamp + "\n" + envelope.nonce + "\n" + envelope.payload;
            var expected = hmac(config.sharedSecret(), signed);
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), envelope.signature.getBytes(StandardCharsets.US_ASCII))) {
                return Response.failure("unauthorized", "invalid request signature");
            }
            evictNonces(now);
            var nonceKey = agentId + '\u0000' + envelope.nonce;
            if (nonces.size() >= config.maxNonces() && !nonces.containsKey(nonceKey)) {
                return Response.failure("rate_limited", "nonce cache is at capacity");
            }
            if (nonces.putIfAbsent(nonceKey, now) != null) return Response.failure("unauthorized", "request nonce was already used");
            var command = mapper.readValue(Base64.getUrlDecoder().decode(envelope.payload), Command.class);
            if (command == null || command.operation == null || command.name == null || command.name.isBlank()) {
                return Response.failure("invalid_request", "operation and backend name are required");
            }
            if (!agentId.equals(command.name.trim()) || command.name.length() > 128
                    || command.instanceId == null || command.instanceId.isBlank() || command.instanceId.length() > 128) {
                return Response.failure("unauthorized", "agent may only operate its own backend with an instance ID");
            }
            if (("backend:" + agentId + ":" + command.instanceId).length() > 256) {
                return Response.failure("invalid_request", "backend name and instance ID are too long");
            }
            return switch (command.operation) {
                case "register" -> register(agentId, command);
                case "heartbeat" -> heartbeat(agentId, command);
                case "unregister" -> unregister(agentId, command);
                case "stream" -> openStream(agentId, command, connection, stream);
                default -> Response.failure("invalid_request", "unsupported operation");
            };
        } catch (IllegalArgumentException exception) {
            return Response.failure("invalid_request", exception.getMessage());
        } catch (Exception exception) {
            return Response.failure("request_failed", rootMessage(exception));
        }
    }

    private Response register(String agentId, Command command) throws Exception {
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
        var name = command.name.trim();
        var lock = leaseLock(name);
        lock.lock();
        try {
            var result = servers.register(registration).toCompletableFuture().get(5, TimeUnit.SECONDS);
            if (result.success()) {
                var previous = leases.put(name, new Lease(agentId, command.instanceId, System.currentTimeMillis(), persistence));
                if (previous != null && !previous.instanceId.equals(command.instanceId)) broker.remove(name, previous.instanceId);
            }
            return response(result);
        } finally {
            lock.unlock();
        }
    }

    private Response heartbeat(String agentId, Command command) {
        var name = command.name.trim();
        var lock = leaseLock(name);
        lock.lock();
        try {
            var lease = leases.get(name);
            if (lease == null || !lease.ownedBy(agentId, command.instanceId)) {
                return Response.failure("not_registered", "backend must register this instance before sending heartbeats");
            }
            leases.put(name, new Lease(agentId, command.instanceId, System.currentTimeMillis(), lease.persistence));
            return Response.success("heartbeat", "");
        } finally {
            lock.unlock();
        }
    }

    private Response unregister(String agentId, Command command) throws Exception {
        var name = command.name.trim();
        var lock = leaseLock(name);
        lock.lock();
        try {
            var lease = leases.get(name);
            if (lease == null || !lease.ownedBy(agentId, command.instanceId)) return Response.failure("not_registered", "backend instance is not registered");
            var result = servers.unregister(command.name, new ServerRemoval(true, false, Duration.ZERO, lease.persistence))
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            if (result.success()) {
                leases.remove(name, lease);
                broker.remove(name, lease.instanceId);
            }
            return response(result);
        } finally {
            lock.unlock();
        }
    }

    void expireLeases() {
        var cutoff = System.currentTimeMillis() - config.heartbeatTimeout().toMillis();
        leases.forEach((name, observedLease) -> {
            if (observedLease.lastSeen >= cutoff) {
                return;
            }
            var lock = leaseLock(name);
            lock.lock();
            try {
                var lease = leases.get(name);
                if (lease == null || lease != observedLease || lease.lastSeen >= cutoff) {
                    return;
                }
                var result = servers.unregister(name, new ServerRemoval(true, false, Duration.ZERO, lease.persistence))
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
                if (result.success() || "not_found".equals(result.outcome())) {
                    leases.remove(name, lease);
                    broker.remove(name, lease.instanceId);
                }
            } catch (Exception ignored) {
                // Keep the lease so a later sweep can retry a transient server-service failure.
            } finally {
                lock.unlock();
            }
        });
    }

    private ReentrantLock leaseLock(String name) {
        return leaseLocks[Math.floorMod(name.hashCode(), leaseLocks.length)];
    }

    private Response openStream(String agentId, Command command, Socket connection, StreamHolder holder) throws IOException {
        var name = command.name.trim();
        var lock = leaseLock(name);
        lock.lock();
        try {
            var lease = leases.get(name);
            if (lease == null || !lease.ownedBy(agentId, command.instanceId)) {
                return Response.failure("not_registered", "backend instance must register before opening a stream");
            }
            if (!streamSlots.tryAcquire()) return Response.failure("stream_capacity_full", "backend agent stream capacity is full");
            var session = new AgentStream(name, command.instanceId, connection);
            if (!broker.attach(name, command.instanceId, session)) {
                streamSlots.release();
                return Response.failure("broker_closed", "message broker is closed");
            }
            holder.session = session;
            streams.add(session);
            return Response.success("stream_connected", "");
        } finally {
            lock.unlock();
        }
    }

    private static final class StreamHolder {
        private AgentStream session;
    }

    private final class AgentStream implements BackendMessageBroker.Session {
        private final String name;
        private final String instanceId;
        private final Socket connection;
        private final ArrayBlockingQueue<ChannelFrame> outgoing = new ArrayBlockingQueue<>(256);
        private final AtomicBoolean running = new AtomicBoolean();
        private final AtomicBoolean released = new AtomicBoolean();
        private volatile boolean stopped;

        private AgentStream(String name, String instanceId, Socket connection) {
            this.name = name;
            this.instanceId = instanceId;
            this.connection = connection;
        }

        private void run() {
            if (stopped) return;
            var writer = new Thread(() -> {
                try {
                    while (!stopped) {
                        var frame = outgoing.take();
                        ChannelWire.write(connection.getOutputStream(), frame);
                    }
                } catch (InterruptedException | IOException ignored) {
                    close();
                }
            }, "strataproxy-agent-stream-write-" + name);
            writer.setDaemon(true);
            writer.start();
            try {
                ChannelFrame frame;
                while (!stopped && (frame = ChannelWire.read(connection.getInputStream())) != null) {
                    switch (frame.type) {
                        case ChannelFrame.SUBSCRIBE -> broker.subscribe(name, instanceId, this, frame.channel);
                        case ChannelFrame.UNSUBSCRIBE -> broker.unsubscribe(name, instanceId, this, frame.channel);
                        case ChannelFrame.ACK -> broker.acknowledge(name, instanceId, this, frame.messageId);
                        case ChannelFrame.PUBLISH -> {
                            var result = broker.publishFromBackend(name, instanceId, this, frame);
                            if (!offer(new ChannelFrame(ChannelFrame.RESULT, frame.requestId, result.messageId(), "", "", "", "",
                                    result.outcome(), new byte[] {(byte) (result.accepted() ? 1 : 0)}))) {
                                throw new IOException("agent response queue is full");
                            }
                        }
                        default -> throw new IOException("unsupported agent frame type");
                    }
                }
            } catch (IOException ignored) {
                // Closing or a malformed frame terminates only this authenticated stream.
            } finally {
                broker.detach(name, instanceId, this);
                streams.remove(this);
                close();
                writer.interrupt();
            }
        }

        @Override public boolean offer(ChannelFrame frame) { return !stopped && outgoing.offer(frame); }
        @Override public int remainingCapacity() { return outgoing.remainingCapacity(); }
        @Override public void close() {
            stopped = true;
            try { connection.close(); } catch (IOException ignored) { }
            if (released.compareAndSet(false, true)) streamSlots.release();
        }
    }

    private String hmac(String secret, String value) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private String readBoundedLine(InputStream input) throws IOException {
        var bytes = new java.io.ByteArrayOutputStream(Math.min(MAX_LINE_LENGTH, 1024));
        for (int value; (value = input.read()) != -1;) {
            if (value == '\n') return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            if (value != '\r') {
                if (bytes.size() >= MAX_LINE_LENGTH) return null;
                bytes.write(value);
            }
        }
        return bytes.size() == 0 ? null : new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private void evictNonces(long now) {
        var cutoff = now - MAX_CLOCK_SKEW_MILLIS;
        nonces.entrySet().removeIf(entry -> entry.getValue() < cutoff);
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
        streams.forEach(AgentStream::close);
        streams.clear();
        if (ownsBroker) broker.close();
    }

    private record Lease(String agentId, String instanceId, long lastSeen, ServerPersistence persistence) {
        boolean ownedBy(String expectedAgentId, String expectedInstanceId) {
            return agentId.equals(expectedAgentId) && instanceId.equals(expectedInstanceId);
        }
    }

    public static final class Envelope {
        public String agentId;
        public long timestamp;
        public String nonce;
        public String payload;
        public String signature;
    }

    public static final class Command {
        public String operation;
        public String name;
        public String instanceId;
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

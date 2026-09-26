package dev.strataproxy.core.control;

import dev.strataproxy.api.BackendMessage;
import dev.strataproxy.api.BackendSendResult;
import dev.strataproxy.app.ProxyConfiguration;
import dev.strataproxy.core.backend.BackendCatalog;
import dev.strataproxy.core.backend.BackendHandle;
import dev.strataproxy.core.backend.BackendId;
import dev.strataproxy.core.backend.BackendOwner;
import dev.strataproxy.core.backend.BackendRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/** Independent authenticated backend connection for registration and plugin control messages. */
public final class BackendControlService implements BackendChannelTransport, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackendControlService.class);
    private static final int VERSION = 1;
    private static final int MAX_FRAME = 66 * 1024;
    private static final int MAX_PAYLOAD = 65_536;
    private static final long MAX_QUEUED_BYTES = 1_048_576;
    private static final int MAX_REQUESTS = 128;
    private static final int HELLO = 1, REGISTER = 2, REGISTERED = 3, HEARTBEAT = 4,
            PONG = 5, GOODBYE = 6, MESSAGE = 7, RESPONSE = 8;
    private static final int OK = 0, NO_HANDLER = 1, ERROR = 2;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final ProxyConfiguration.BackendChannel configuration;
    private final BackendCatalog catalog;
    private final Function<BackendMessage, CompletionStage<byte[]>> inbound;
    private final Object lock = new Object();
    private final Map<String, Lease> byInstance = new HashMap<>();
    private final Map<String, Lease> byBackend = new HashMap<>();
    private final Semaphore connectionSlots;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("strataproxy-control-timer", 0).factory());
    private final SecureRandom random = new SecureRandom();
    private final AtomicLong nextEpoch = new AtomicLong();
    private volatile boolean closed;
    private ServerSocket listener;

    public BackendControlService(ProxyConfiguration.BackendChannel configuration, BackendCatalog catalog,
                                 Function<BackendMessage, CompletionStage<byte[]>> inbound) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.inbound = Objects.requireNonNull(inbound, "inbound");
        this.connectionSlots = new Semaphore(configuration.maxConnections());
    }

    public synchronized void start() throws IOException {
        if (listener != null || closed) throw new IllegalStateException("control listener already started or closed");
        ServerSocket server = new ServerSocket();
        try {
            server.bind(configuration.listenAddress(), 32);
            listener = server;
            timer.scheduleAtFixedRate(this::expireLeases, 1, 1, TimeUnit.SECONDS);
            Thread.ofVirtual().name("strataproxy-control-accept").start(this::acceptLoop);
            LOGGER.info("Backend control listening on {}", server.getLocalSocketAddress());
        } catch (RuntimeException | IOException failure) {
            server.close();
            throw failure;
        }
    }

    public java.net.SocketAddress localAddress() {
        ServerSocket server = listener;
        return server == null ? null : server.getLocalSocketAddress();
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket socket = listener.accept();
                if (!connectionSlots.tryAcquire()) {
                    socket.close();
                    continue;
                }
                Thread.ofVirtual().name("strataproxy-control-peer").start(() -> {
                    try { serve(socket); }
                    finally { connectionSlots.release(); }
                });
            } catch (IOException failure) {
                if (!closed) LOGGER.warn("Backend control accept failed", failure);
            }
        }
    }

    private void serve(Socket socket) {
        Connection connection = new Connection(socket);
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(5_000);
            byte[] nonce = new byte[32];
            random.nextBytes(nonce);
            connection.write(HELLO, out -> { out.writeInt(VERSION); out.write(nonce); });
            Frame register = readFrame(connection.input);
            if (register.type != REGISTER) throw new IOException("expected backend registration");
            Registration requested = parseRegistration(register.input, nonce);
            register(connection, requested);
            connection.write(REGISTERED, out -> out.writeLong(connection.epoch));
            socket.setSoTimeout(configuration.leaseSeconds() * 1_000);
            while (!closed && connection.live.get()) {
                Frame frame = readFrame(connection.input);
                if (!isCurrent(connection)) break;
                switch (frame.type) {
                    case HEARTBEAT -> {
                        requireEmpty(frame.input);
                        renew(connection);
                        connection.write(PONG, ignored -> { });
                    }
                    case GOODBYE -> {
                        requireEmpty(frame.input);
                        unregister(connection);
                        return;
                    }
                    case MESSAGE -> receiveMessage(connection, frame.input);
                    case RESPONSE -> receiveResponse(connection, frame.input);
                    default -> throw new IOException("unexpected backend control frame " + frame.type);
                }
            }
        } catch (EOFException | SocketException expected) {
            // A connection loss retains registration until its lease expires.
        } catch (Exception failure) {
            if (!closed) LOGGER.debug("Backend control connection ended: {}", failure.toString());
        } finally {
            disconnect(connection);
        }
    }

    private Registration parseRegistration(DataInputStream input, byte[] nonce) throws Exception {
        String instanceId = readString(input, 64);
        String name = readString(input, 128);
        String address = readString(input, 1024);
        String generation = readString(input, 64);
        String keyId = readString(input, 64);
        byte[] signature = input.readNBytes(32);
        if (signature.length != 32) throw new EOFException();
        requireEmpty(input);
        UUID.fromString(generation);
        ProxyConfiguration.Client client = configuration.clients().get(instanceId);
        if (client == null || !client.backendName().equals(name) || !client.keyId().equals(keyId))
            throw new IOException("backend identity rejected");
        URI uri = URI.create(address);
        new BackendRegistration(new BackendId(name), new BackendOwner("backend-control:" + instanceId, 0), uri);
        if (!client.allowedHosts().contains(uri.getHost())) throw new IOException("advertised backend host rejected");
        ByteArrayOutputStream canonical = new ByteArrayOutputStream();
        canonical.write(nonce);
        DataOutputStream signed = new DataOutputStream(canonical);
        writeString(signed, instanceId);
        writeString(signed, name);
        writeString(signed, address);
        writeString(signed, generation);
        writeString(signed, keyId);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(client.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        if (!MessageDigest.isEqual(signature, mac.doFinal(canonical.toByteArray())))
            throw new IOException("backend signature rejected");
        return new Registration(instanceId, name, generation, uri, client.allowedNamespaces());
    }

    private void register(Connection connection, Registration requested) throws IOException {
        synchronized (lock) {
            if (closed) throw new IOException("control service is closed");
            Lease conflict = byBackend.get(requested.name);
            if (conflict != null && !conflict.instanceId.equals(requested.instanceId))
                throw new IOException("backend name already registered");
            Lease prior = byInstance.get(requested.instanceId);
            if (prior != null && !prior.name.equals(requested.name))
                throw new IOException("instance cannot change backend name");
            long epoch = nextEpoch.incrementAndGet();
            BackendOwner owner = new BackendOwner("backend-control:" + requested.instanceId, epoch);
            BackendRegistration registration = new BackendRegistration(new BackendId(requested.name), owner,
                    requested.address, Map.of("discovery", "control"),
                    Map.of("instance.id", requested.instanceId, "instance.generation", requested.generation));
            BackendHandle handle;
            try { handle = catalog.register(registration).handle(); }
            catch (RuntimeException conflictFailure) { throw new IOException("backend registration rejected", conflictFailure); }
            connection.instanceId = requested.instanceId;
            connection.name = requested.name;
            connection.epoch = epoch;
            connection.allowedNamespaces = requested.allowedNamespaces;
            Lease replacement = new Lease(requested.instanceId, requested.name, requested.generation, epoch, handle,
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(configuration.leaseSeconds()), connection);
            byInstance.put(requested.instanceId, replacement);
            byBackend.put(requested.name, replacement);
            if (prior != null && prior.connection != null) prior.connection.close();
        }
    }

    private boolean isCurrent(Connection connection) {
        synchronized (lock) {
            Lease lease = byInstance.get(connection.instanceId);
            return lease != null && lease.connection == connection && lease.epoch() == connection.epoch;
        }
    }

    private void renew(Connection connection) {
        synchronized (lock) {
            Lease lease = byInstance.get(connection.instanceId);
            if (lease != null && lease.connection == connection)
                lease.expiresAtNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(configuration.leaseSeconds());
        }
    }

    private void unregister(Connection connection) {
        synchronized (lock) {
            Lease lease = byInstance.get(connection.instanceId);
            if (lease != null && lease.connection == connection) removeLease(lease);
        }
    }

    private void disconnect(Connection connection) {
        connection.close();
        synchronized (lock) {
            Lease lease = byInstance.get(connection.instanceId);
            if (lease != null && lease.connection == connection) lease.connection = null;
        }
    }

    private void expireLeases() {
        synchronized (lock) {
            long now = System.nanoTime();
            for (Lease lease : java.util.List.copyOf(byInstance.values())) {
                if (lease.expiresAtNanos <= now) removeLease(lease);
            }
        }
    }

    private void removeLease(Lease lease) {
        if (byInstance.get(lease.instanceId) != lease) return;
        byInstance.remove(lease.instanceId);
        byBackend.remove(lease.name, lease);
        catalog.remove(lease.handle);
        if (lease.connection != null) lease.connection.close();
    }

    private void receiveMessage(Connection connection, DataInputStream input) throws IOException {
        String channel = readString(input, 128);
        validateChannel(channel);
        if (!connection.allowedNamespaces.contains(channel.substring(0, channel.indexOf(':'))))
            throw new IOException("backend message namespace rejected");
        if (!connection.allowMessage()) throw new IOException("backend message rate exceeded");
        long requestId = input.readLong();
        byte[] payload = readPayload(input);
        requireEmpty(input);
        if (connection.inboundRequests.incrementAndGet() > MAX_REQUESTS) {
            connection.inboundRequests.decrementAndGet();
            throw new IOException("too many backend requests");
        }
        BackendMessage message = new BackendMessage(connection.name, connection.instanceId,
                connection.epoch, channel, payload);
        CompletionStage<byte[]> result;
        try { result = Objects.requireNonNull(inbound.apply(message), "handler result"); }
        catch (Throwable failure) { result = CompletableFuture.failedFuture(failure); }
        result.whenComplete((reply, failure) -> {
            connection.inboundRequests.decrementAndGet();
            if (requestId == 0 || !isCurrent(connection)) return;
            Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                    ? failure.getCause() : failure;
            int status = failure == null ? OK : cause instanceof BackendNoHandlerException ? NO_HANDLER : ERROR;
            byte[] response = failure == null && reply != null ? reply.clone() : new byte[0];
            if (response.length > MAX_PAYLOAD) { status = ERROR; response = new byte[0]; }
            int finalStatus = status;
            byte[] finalResponse = response;
            Thread.ofVirtual().start(() -> {
                try { connection.write(RESPONSE, out -> {
                    out.writeLong(requestId);
                    out.writeByte(finalStatus);
                    writePayload(out, finalResponse);
                }); }
                catch (IOException ignored) { connection.close(); }
            });
        });
    }

    private void receiveResponse(Connection connection, DataInputStream input) throws IOException {
        long requestId = input.readLong();
        int status = input.readUnsignedByte();
        byte[] payload = readPayload(input);
        requireEmpty(input);
        CompletableFuture<byte[]> request = connection.pending.remove(requestId);
        if (request != null) {
            if (status == OK) request.complete(payload);
            else request.completeExceptionally(new IOException("backend response status " + status));
        }
    }

    @Override public CompletionStage<BackendSendResult> send(String backendName, String channel, byte[] payload) {
        validateMessage(channel, payload);
        Connection connection = current(backendName);
        if (connection == null) return CompletableFuture.completedFuture(BackendSendResult.NOT_CONNECTED);
        byte[] copy = payload.clone();
        if (!connection.reserve(copy.length)) return CompletableFuture.completedFuture(BackendSendResult.BACKPRESSURED);
        CompletableFuture<BackendSendResult> result = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                connection.write(MESSAGE, out -> {
                    writeString(out, channel);
                    out.writeLong(0);
                    writePayload(out, copy);
                });
                result.complete(BackendSendResult.SENT);
            } catch (IOException failure) { connection.close(); result.completeExceptionally(failure); }
            finally { connection.release(copy.length); }
        });
        return result;
    }

    @Override public CompletionStage<byte[]> request(String backendName, String channel, byte[] payload) {
        validateMessage(channel, payload);
        Connection connection = current(backendName);
        if (connection == null) return CompletableFuture.failedFuture(new IOException("backend control is disconnected"));
        byte[] copy = payload.clone();
        if (!connection.reserve(copy.length))
            return CompletableFuture.failedFuture(new IOException("backend control is backpressured"));
        if (!connection.requestSlots.tryAcquire()) {
            connection.release(copy.length);
            return CompletableFuture.failedFuture(new IOException("backend control has too many pending requests"));
        }
        long id = connection.nextRequest.getAndIncrement();
        if (id <= 0) {
            connection.release(copy.length);
            connection.requestSlots.release();
            return CompletableFuture.failedFuture(new IOException("backend request ID exhausted"));
        }
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        connection.pending.put(id, result);
        final ScheduledFuture<?> timeout;
        try {
            timeout = timer.schedule(() -> result.completeExceptionally(new IOException("backend request timed out")),
                    REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RuntimeException failure) {
            connection.pending.remove(id, result);
            connection.requestSlots.release();
            connection.release(copy.length);
            result.completeExceptionally(failure);
            return result;
        }
        result.whenComplete((ignored, failure) -> {
            connection.pending.remove(id, result);
            timeout.cancel(false);
            connection.requestSlots.release();
        });
        Thread.ofVirtual().start(() -> {
            try { connection.write(MESSAGE, out -> {
                writeString(out, channel);
                out.writeLong(id);
                writePayload(out, copy);
            }); }
            catch (IOException failure) { connection.close(); result.completeExceptionally(failure); }
            finally { connection.release(copy.length); }
        });
        return result;
    }

    private Connection current(String name) {
        synchronized (lock) {
            Lease lease = byBackend.get(name);
            return lease == null || lease.connection == null || !lease.connection.live.get()
                    ? null : lease.connection;
        }
    }

    private static void validateMessage(String channel, byte[] payload) {
        validateChannel(channel);
        Objects.requireNonNull(payload, "payload");
        if (payload.length > MAX_PAYLOAD) throw new IllegalArgumentException("backend payload exceeds 64 KiB");
    }

    private static void validateChannel(String channel) {
        if (channel == null || !channel.matches("[a-z0-9][a-z0-9_.-]{0,63}:[a-z0-9][a-z0-9_.-]{0,63}"))
            throw new IllegalArgumentException("invalid backend message channel");
    }

    private static Frame readFrame(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > MAX_FRAME) throw new IOException("invalid backend control frame length");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException();
        DataInputStream body = new DataInputStream(new ByteArrayInputStream(bytes));
        return new Frame(body.readUnsignedByte(), body);
    }

    private static String readString(DataInputStream input, int maximum) throws IOException {
        int length = input.readUnsignedShort();
        if (length > maximum) throw new IOException("control string too long");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException();
        String decoded = new String(bytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(decoded.getBytes(StandardCharsets.UTF_8), bytes)) throw new IOException("invalid UTF-8");
        return decoded;
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65_535) throw new IOException("control string too long");
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static byte[] readPayload(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_PAYLOAD) throw new IOException("invalid backend payload length");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException();
        return bytes;
    }

    private static void writePayload(DataOutputStream output, byte[] bytes) throws IOException {
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static void requireEmpty(DataInputStream input) throws IOException {
        if (input.available() != 0) throw new IOException("trailing backend control bytes");
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (listener != null) {
            try { listener.close(); } catch (IOException ignored) { }
        }
        timer.shutdownNow();
        synchronized (lock) {
            for (Lease lease : java.util.List.copyOf(byInstance.values())) removeLease(lease);
        }
    }

    private record Registration(String instanceId, String name, String generation, URI address,
                                java.util.Set<String> allowedNamespaces) { }
    private record Frame(int type, DataInputStream input) { }
    private interface Writer { void write(DataOutputStream output) throws IOException; }

    private static final class Lease {
        private final String instanceId, name, generation;
        private final long epoch;
        private final BackendHandle handle;
        private long expiresAtNanos;
        private Connection connection;
        private Lease(String instanceId, String name, String generation, long epoch, BackendHandle handle,
                      long expiresAtNanos, Connection connection) {
            this.instanceId = instanceId;
            this.name = name;
            this.generation = generation;
            this.epoch = epoch;
            this.handle = handle;
            this.expiresAtNanos = expiresAtNanos;
            this.connection = connection;
        }
        private long epoch() { return epoch; }
    }

    private static final class Connection {
        private final Socket socket;
        private final DataInputStream input;
        private final DataOutputStream output;
        private final Object writeLock = new Object();
        private final AtomicBoolean live = new AtomicBoolean(true);
        private final AtomicLong queuedBytes = new AtomicLong();
        private final AtomicInteger queuedMessages = new AtomicInteger();
        private final AtomicLong nextRequest = new AtomicLong(1);
        private final ConcurrentHashMap<Long, CompletableFuture<byte[]>> pending = new ConcurrentHashMap<>();
        private final AtomicInteger inboundRequests = new AtomicInteger();
        private final Semaphore requestSlots = new Semaphore(MAX_REQUESTS);
        private String instanceId, name;
        private java.util.Set<String> allowedNamespaces = java.util.Set.of();
        private long epoch;
        private long rateWindowNanos = System.nanoTime();
        private int messagesInWindow;
        private Connection(Socket socket) {
            this.socket = socket;
            try {
                this.input = new DataInputStream(socket.getInputStream());
                this.output = new DataOutputStream(socket.getOutputStream());
            } catch (IOException failure) { throw new IllegalStateException(failure); }
        }
        private boolean reserve(int size) {
            if (queuedMessages.incrementAndGet() > MAX_REQUESTS) {
                queuedMessages.decrementAndGet();
                return false;
            }
            for (;;) {
                long used = queuedBytes.get();
                if (used + size > MAX_QUEUED_BYTES) {
                    queuedMessages.decrementAndGet();
                    return false;
                }
                if (queuedBytes.compareAndSet(used, used + size)) return true;
            }
        }
        private void release(int size) {
            queuedBytes.addAndGet(-size);
            queuedMessages.decrementAndGet();
        }
        private boolean allowMessage() {
            long now = System.nanoTime();
            if (now - rateWindowNanos >= TimeUnit.SECONDS.toNanos(1)) {
                rateWindowNanos = now;
                messagesInWindow = 0;
            }
            return ++messagesInWindow <= 200;
        }
        private void write(int type, Writer writer) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream frame = new DataOutputStream(bytes);
            frame.writeByte(type);
            writer.write(frame);
            if (bytes.size() > MAX_FRAME) throw new IOException("control frame too large");
            synchronized (writeLock) {
                if (!live.get()) throw new SocketException("backend control disconnected");
                output.writeInt(bytes.size());
                bytes.writeTo(output);
                output.flush();
            }
        }
        private void close() {
            if (!live.compareAndSet(true, false)) return;
            try { socket.close(); } catch (IOException ignored) { }
            for (CompletableFuture<byte[]> result : pending.values())
                result.completeExceptionally(new IOException("backend control disconnected"));
            pending.clear();
        }
    }
}

package dev.moonbridge.core.control;

import dev.moonbridge.app.ProxyConfiguration;
import dev.moonbridge.core.backend.BackendCatalog;
import dev.moonbridge.core.backend.BackendHandle;
import dev.moonbridge.core.backend.BackendId;
import dev.moonbridge.core.backend.BackendOwner;
import dev.moonbridge.core.backend.BackendRegistration;
import dev.moonbridge.messaging.Endpoint;
import dev.moonbridge.messaging.Message;
import dev.moonbridge.messaging.MessageKind;
import dev.moonbridge.messaging.MessagingException;
import dev.moonbridge.messaging.PublishResult;
import dev.moonbridge.messaging.SendResult;
import dev.moonbridge.messaging.internal.LocalMessaging;
import dev.moonbridge.messaging.protocol.MessageCodec;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;

/** Independent authenticated backend connection for registration and plugin control messages. */
public final class BackendControlService implements BackendChannelTransport, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackendControlService.class);
    private static final int VERSION = 2;
    private static final int MAX_FRAME = MessageCodec.MAX_FRAME_BYTES;
    private static final long MAX_QUEUED_BYTES = 1_048_576;
    private static final long MAX_GLOBAL_QUEUED_BYTES = 64L * 1_048_576;
    private static final int MAX_WRITE_TASKS = 512;
    private static final int MAX_REQUESTS = 128;
    private static final int HELLO = 1, REGISTER = 2, REGISTERED = 3, HEARTBEAT = 4,
            PONG = 5, GOODBYE = 6;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final ProxyConfiguration.BackendChannel configuration;
    private final BackendCatalog catalog;
    private final LocalMessaging messaging;
    private final Object lock = new Object();
    private final Map<String, Lease> byInstance = new HashMap<>();
    private final Map<String, Lease> byBackend = new HashMap<>();
    private final Semaphore connectionSlots;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("moonbridge-control-timer", 0).factory());
    private final SecureRandom random = new SecureRandom();
    private final AtomicLong nextEpoch = new AtomicLong();
    private final AtomicLong globalQueuedBytes = new AtomicLong();
    private final Semaphore writeTasks = new Semaphore(MAX_WRITE_TASKS);
    private final ThreadPoolExecutor routingWorkers = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(512), namedFactory("moonbridge-control-routing"),
            new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean closed;
    private ServerSocket listener;

    public BackendControlService(ProxyConfiguration.BackendChannel configuration, BackendCatalog catalog,
                                 LocalMessaging messaging) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.messaging = Objects.requireNonNull(messaging, "messaging");
        this.connectionSlots = new Semaphore(configuration.maxConnections());
    }

    public synchronized void start() throws IOException {
        if (listener != null || closed) throw new IllegalStateException("control listener already started or closed");
        ServerSocket server = new ServerSocket();
        try {
            server.bind(configuration.listenAddress(), 32);
            listener = server;
            timer.scheduleAtFixedRate(this::expireLeases, 1, 1, TimeUnit.SECONDS);
            Thread.ofVirtual().name("moonbridge-control-accept").start(this::acceptLoop);
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
                Thread.ofVirtual().name("moonbridge-control-peer").start(() -> {
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
                        writeAsync(connection, singleByteFrame(PONG));
                    }
                    case GOODBYE -> {
                        requireEmpty(frame.input);
                        unregister(connection);
                        return;
                    }
                    case MessageCodec.MESSAGE -> receiveMessage(connection, frame.bytes);
                    case MessageCodec.RESPONSE -> receiveResponse(connection, frame.bytes);
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
        return new Registration(instanceId, name, generation, uri, client.allowedNamespaces(),
                client.allowedReceiveNamespaces());
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
            connection.allowedReceiveNamespaces = requested.allowedReceiveNamespaces;
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

    private void receiveMessage(Connection connection, byte[] frame) throws IOException {
        MessageCodec.IncomingMessage incoming = MessageCodec.decodeMessage(frame);
        if (!connection.allowMessage()) throw new IOException("backend message rate exceeded");
        Message received = incoming.message;
        if (received.kind() == MessageKind.REPLY) throw new IOException("backend cannot send an unsolicited reply message");
        long deadline = deadlineAfterMillis(incoming.timeoutMillis);
        String namespace = namespace(received.channel());
        if (!connection.allowedNamespaces.contains(namespace)) {
            if (received.kind() == MessageKind.EVENT && received.target() != null) {
                writeAsync(connection, MessageCodec.sendResult(incoming.operationId, SendResult.REJECTED), deadline);
            } else {
                writeAsync(connection, MessageCodec.error(incoming.operationId, MessagingException.Code.REJECTED,
                        "backend message namespace rejected"), deadline);
            }
            return;
        }
        Message authenticated = new Message(received.id(), received.kind(), received.channel(),
                Endpoint.backend(connection.name), received.target(), received.replyTo(), received.payload());
        if (connection.inboundRequests.incrementAndGet() > MAX_REQUESTS) {
            connection.inboundRequests.decrementAndGet();
            writeAsync(connection, MessageCodec.error(incoming.operationId,
                    MessagingException.Code.BACKPRESSURED, "inbound operation limit reached"), deadline);
            return;
        }
        switch (authenticated.kind()) {
            case EVENT:
                if (authenticated.target() == null) {
                    routePublish(connection, incoming.operationId, authenticated, deadline);
                } else {
                    routeSend(connection, incoming.operationId, authenticated, deadline);
                }
                break;
            case REQUEST:
                routeRequest(connection, incoming.operationId, authenticated, deadline);
                break;
            default:
                connection.inboundRequests.decrementAndGet();
                throw new IOException("unsupported backend message kind");
        }
    }

    private void routeSend(Connection origin, long operationId, Message message, long deadline) {
        CompletionStage<SendResult> delivery;
        Endpoint target = message.target();
        if (target.isProxy()) {
            delivery = CompletableFuture.completedFuture(messaging.receiveEvent(message));
        } else {
            Connection destination = current(target.backendName());
            if (destination == null) {
                delivery = CompletableFuture.completedFuture(SendResult.NOT_CONNECTED);
            } else if (!destination.allowedReceiveNamespaces.contains(namespace(message.channel()))) {
                delivery = CompletableFuture.completedFuture(SendResult.REJECTED);
            } else {
                Message forwarded = retarget(message, target);
                delivery = sendTo(destination, forwarded, remaining(deadline));
            }
        }
        delivery.whenComplete((result, failure) -> {
            if (failure != null) respondError(origin, operationId, asMessagingException(failure), deadline);
            else respondSend(origin, operationId, result, deadline);
            origin.inboundRequests.decrementAndGet();
        });
    }

    private void routePublish(Connection origin, long operationId, Message event, long deadline) {
        List<Connection> destinations = connectedBackends().stream()
                .filter(candidate -> candidate.allowedReceiveNamespaces.contains(namespace(event.channel())))
                .toList();
        if (destinations.size() + 1 > MessageCodec.MAX_PUBLISH_RESULTS) {
            respondError(origin, operationId, new MessagingException(MessagingException.Code.REJECTED,
                    "publish exceeds the maximum of " + MessageCodec.MAX_PUBLISH_RESULTS + " authorized nodes"), deadline);
            origin.inboundRequests.decrementAndGet();
            return;
        }
        Map<Endpoint, SendResult> results = new LinkedHashMap<>();
        results.put(Endpoint.proxy(), messaging.receiveEvent(event));
        List<CompletableFuture<Void>> completions = new ArrayList<>();
        for (Connection destination : destinations) {
            Endpoint endpoint = Endpoint.backend(destination.name);
            Message forwarded = retarget(event, endpoint);
            CompletableFuture<Void> completion = new CompletableFuture<>();
            completions.add(completion);
            sendTo(destination, forwarded, remaining(deadline)).whenComplete((status, failure) -> {
                synchronized (results) {
                    results.put(endpoint, failure == null ? status : sendFailure(failure));
                }
                completion.complete(null);
            });
        }
        CompletableFuture.allOf(completions.toArray(CompletableFuture[]::new)).whenComplete((ignored, failure) -> {
            Map<Endpoint, SendResult> copy;
            synchronized (results) { copy = new LinkedHashMap<>(results); }
            try { writeAsync(origin, MessageCodec.published(operationId, new PublishResult(event.id(), copy)), deadline); }
            catch (IOException invalid) { writeAsync(origin, errorFrame(operationId, invalid), deadline); }
            finally { origin.inboundRequests.decrementAndGet(); }
        });
    }

    private void routeRequest(Connection origin, long operationId, Message request, long deadline) {
        CompletionStage<Message> response;
        Endpoint target = request.target();
        if (target.isProxy()) {
            response = dispatchProxyRequest(origin, request, deadline);
        } else {
            Connection destination = current(target.backendName());
            if (destination == null) {
                response = failed(new MessagingException(MessagingException.Code.NOT_CONNECTED,
                        "target backend is not connected"));
            } else if (!destination.allowedReceiveNamespaces.contains(namespace(request.channel()))) {
                response = failed(new MessagingException(MessagingException.Code.REJECTED,
                        "target backend does not allow this message namespace"));
            } else {
                response = requestFrom(destination, request, remaining(deadline));
            }
        }
        response.whenComplete((reply, failure) -> {
            if (failure != null) respondError(origin, operationId, asMessagingException(failure), deadline);
            else {
                try { writeAsync(origin, MessageCodec.reply(operationId, reply), deadline); }
                catch (IOException invalid) { writeAsync(origin, errorFrame(operationId, invalid), deadline); }
            }
            origin.inboundRequests.decrementAndGet();
        });
    }

    private CompletionStage<Message> dispatchProxyRequest(Connection origin, Message request, long deadline) {
        Duration timeout = remaining(deadline);
        if (timeout.isZero()) return failed(new MessagingException(MessagingException.Code.TIMED_OUT,
                "request expired before proxy dispatch"));
        return messaging.receiveRequest(retarget(request, Endpoint.proxy()), timeout);
    }

    @Override public CompletionStage<SendResult> send(Message message) {
        Objects.requireNonNull(message, "message");
        if (!Endpoint.proxy().equals(message.source()) || message.kind() != MessageKind.EVENT
                || message.target() == null || message.target().isProxy()) {
            return failed(new MessagingException(MessagingException.Code.REJECTED,
                    "proxy send must be an event targeted at a backend"));
        }
        Connection connection = current(message.target().backendName());
        if (connection == null) return CompletableFuture.completedFuture(SendResult.NOT_CONNECTED);
        if (!connection.allowedReceiveNamespaces.contains(namespace(message.channel())))
            return CompletableFuture.completedFuture(SendResult.REJECTED);
        return sendTo(connection, message, REQUEST_TIMEOUT);
    }

    @Override public CompletionStage<Message> request(Message message, Duration timeout) {
        Objects.requireNonNull(message, "message");
        if (!Endpoint.proxy().equals(message.source()) || message.kind() != MessageKind.REQUEST
                || message.target() == null || message.target().isProxy()) {
            return failed(new MessagingException(MessagingException.Code.REJECTED,
                    "proxy request must target a backend"));
        }
        Connection connection = current(message.target().backendName());
        if (connection == null) return failed(new MessagingException(
                MessagingException.Code.NOT_CONNECTED, "target backend is not connected"));
        if (!connection.allowedReceiveNamespaces.contains(namespace(message.channel())))
            return failed(new MessagingException(MessagingException.Code.REJECTED,
                    "target backend does not allow this message namespace"));
        return requestFrom(connection, message, timeout);
    }

    @Override public CompletionStage<PublishResult> publish(Message event) {
        Objects.requireNonNull(event, "event");
        if (!Endpoint.proxy().equals(event.source()) || event.kind() != MessageKind.EVENT || event.target() != null)
            return failed(new MessagingException(MessagingException.Code.REJECTED,
                    "proxy publish must be an untargeted event"));
        List<Connection> destinations = connectedBackends().stream()
                .filter(candidate -> candidate.allowedReceiveNamespaces.contains(namespace(event.channel())))
                .toList();
        if (destinations.size() + 1 > MessageCodec.MAX_PUBLISH_RESULTS) {
            return failed(new MessagingException(MessagingException.Code.REJECTED,
                    "publish exceeds the maximum of " + MessageCodec.MAX_PUBLISH_RESULTS + " authorized nodes"));
        }
        Map<Endpoint, SendResult> results = new LinkedHashMap<>();
        List<CompletableFuture<Void>> completions = new ArrayList<>();
        List<CompletableFuture<?>> deliveries = new ArrayList<>();
        for (Connection destination : destinations) {
            Endpoint endpoint = Endpoint.backend(destination.name);
            CompletableFuture<Void> completion = new CompletableFuture<>();
            completions.add(completion);
            CompletionStage<SendResult> delivery = sendTo(destination, retarget(event, endpoint), REQUEST_TIMEOUT);
            deliveries.add(delivery.toCompletableFuture());
            delivery.whenComplete((status, failure) -> {
                synchronized (results) { results.put(endpoint, failure == null ? status : sendFailure(failure)); }
                completion.complete(null);
            });
        }
        CompletableFuture<PublishResult> result = new CompletableFuture<>();
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled()) deliveries.forEach(delivery -> delivery.cancel(false));
        });
        CompletableFuture.allOf(completions.toArray(CompletableFuture[]::new)).whenComplete((ignored, failure) -> {
            synchronized (results) { result.complete(new PublishResult(event.id(), results)); }
        });
        return result;
    }

    private CompletionStage<SendResult> sendTo(Connection connection, Message message, Duration timeout) {
        return mapCancellable(exchange(connection, message, timeout, MessageCodec.Response.Type.SEND),
                response -> response.sendResult);
    }

    private CompletionStage<Message> requestFrom(Connection connection, Message message, Duration timeout) {
        return mapCancellable(exchange(connection, message, timeout, MessageCodec.Response.Type.REPLY), response -> {
            Message reply = response.message;
            if (reply == null || !message.id().equals(reply.replyTo())
                    || !message.channel().equals(reply.channel())
                    || !Endpoint.backend(connection.name).equals(reply.source())
                    || !message.source().equals(reply.target())) {
                throw new MessagingException(MessagingException.Code.PROTOCOL_ERROR,
                        "backend returned a reply for a different request or identity");
            }
            return reply;
        });
    }

    private CompletionStage<MessageCodec.Response> exchange(Connection connection, Message message, Duration timeout,
                                                              MessageCodec.Response.Type expected) {
        if (timeout == null || timeout.isNegative() || timeout.isZero())
            return failed(new MessagingException(MessagingException.Code.TIMED_OUT, "message operation timed out"));
        long timeoutMillis;
        try { timeoutMillis = Math.max(1, Math.min(MessageCodec.MAX_TIMEOUT_MILLIS, timeout.toMillis())); }
        catch (ArithmeticException overflow) { timeoutMillis = MessageCodec.MAX_TIMEOUT_MILLIS; }
        if (!connection.requestSlots.tryAcquire()) return failed(new MessagingException(
                MessagingException.Code.BACKPRESSURED, "backend has too many in-flight operations"));
        long operationId = connection.nextRequest.getAndIncrement();
        if (operationId <= 0) {
            connection.requestSlots.release();
            return failed(new MessagingException(MessagingException.Code.REJECTED,
                    "backend operation ID space exhausted"));
        }
        final byte[] frame;
        try { frame = MessageCodec.message(operationId, timeoutMillis, message); }
        catch (IOException | RuntimeException invalid) {
            connection.requestSlots.release();
            return failed(new MessagingException(MessagingException.Code.REJECTED,
                    "message cannot be encoded", invalid));
        }
        final long deadline = deadlineAfterMillis(timeoutMillis);
        if (!reserveWrite(connection, frame.length)) {
            connection.requestSlots.release();
            return failed(new MessagingException(MessagingException.Code.BACKPRESSURED,
                    "backend outbound queue is full"));
        }
        CompletableFuture<MessageCodec.Response> result = new CompletableFuture<>();
        PendingOperation pending = new PendingOperation(result, expected);
        if (connection.pending.putIfAbsent(operationId, pending) != null) {
            releaseWrite(connection, frame.length);
            connection.requestSlots.release();
            return failed(new MessagingException(MessagingException.Code.REJECTED,
                    "backend operation ID collision"));
        }
        AtomicBoolean writeStarted = new AtomicBoolean();
        ScheduledFuture<?> timeoutTask;
        try {
            timeoutTask = timer.schedule(() -> {
                        if (result.isCancelled()) {
                            if (writeStarted.get()) connection.close();
                        } else if (result.completeExceptionally(new MessagingException(
                                MessagingException.Code.TIMED_OUT, "backend message operation timed out"))
                                && writeStarted.get()) connection.close();
                    },
                    timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException closedTimer) {
            result.completeExceptionally(new MessagingException(MessagingException.Code.CLOSED,
                    "backend control service is closed", closedTimer));
            timeoutTask = null;
        }
        final ScheduledFuture<?> operationTimeout = timeoutTask;
        result.whenComplete((value, failure) -> {
            connection.pending.remove(operationId, pending);
            if (operationTimeout != null && (!result.isCancelled() || !writeStarted.get()))
                operationTimeout.cancel(false);
            connection.requestSlots.release();
        });
        Thread.ofVirtual().name("moonbridge-control-write").start(() -> {
            try {
                boolean written = connection.writeFrame(() -> {
                    long remainingNanos = deadline - System.nanoTime();
                    if (result.isDone() || remainingNanos <= 0) return null;
                    long remainingMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
                    byte[] currentFrame = MessageCodec.message(operationId, remainingMillis, message);
                    writeStarted.set(true);
                    if (result.isDone() || deadline - System.nanoTime() <= 0) {
                        writeStarted.set(false);
                        return null;
                    }
                    return currentFrame;
                }, () -> writeStarted.set(false));
                if (!written && !result.isDone() && deadline - System.nanoTime() <= 0) {
                    result.completeExceptionally(new MessagingException(MessagingException.Code.TIMED_OUT,
                            "backend message operation timed out before write"));
                }
            } catch (IOException failure) {
                connection.close();
                result.completeExceptionally(new MessagingException(MessagingException.Code.NOT_CONNECTED,
                        "backend connection was lost while writing", failure));
            } finally { releaseWrite(connection, frame.length); }
        });
        return result;
    }

    private void receiveResponse(Connection connection, byte[] frame) throws IOException {
        MessageCodec.Response response = MessageCodec.decodeResponse(frame);
        PendingOperation pending = connection.pending.get(response.operationId);
        if (pending == null) return; // A timed-out operation may receive one late receipt.
        try {
            routingWorkers.execute(() -> {
                if (response.type == MessageCodec.Response.Type.ERROR) {
                    pending.result.completeExceptionally(new MessagingException(response.errorCode,
                            response.detail == null || response.detail.isEmpty()
                                    ? "remote message operation failed" : response.detail));
                } else if (response.type != pending.expected) {
                    pending.result.completeExceptionally(new MessagingException(MessagingException.Code.PROTOCOL_ERROR,
                            "backend returned the wrong response type"));
                    connection.close();
                } else {
                    pending.result.complete(response);
                }
            });
        } catch (RejectedExecutionException overloaded) {
            connection.close();
            throw new IOException("backend response dispatch is overloaded", overloaded);
        }
    }

    private void respondSend(Connection connection, long operationId, SendResult result, long deadline) {
        try { writeAsync(connection, MessageCodec.sendResult(operationId, result), deadline); }
        catch (IOException invalid) { writeAsync(connection, errorFrame(operationId, invalid), deadline); }
    }

    private void respondError(Connection connection, long operationId, MessagingException error, long deadline) {
        try { writeAsync(connection, MessageCodec.error(operationId, error.code(), safeDetail(error)), deadline); }
        catch (IOException invalid) { writeAsync(connection, errorFrame(operationId, invalid), deadline); }
    }

    private void writeAsync(Connection connection, byte[] frame) {
        writeAsync(connection, frame, deadlineAfterMillis(REQUEST_TIMEOUT.toMillis()));
    }

    private void writeAsync(Connection connection, byte[] frame, long deadline) {
        if (!reserveWrite(connection, frame.length)) {
            connection.close();
            return;
        }
        AtomicBoolean writeStarted = new AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<ScheduledFuture<?>> flushTimeout =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread.ofVirtual().name("moonbridge-control-write").start(() -> {
            try {
                connection.writeFrame(() -> {
                    if (deadline - System.nanoTime() <= 0) return null;
                    writeStarted.set(true);
                    if (deadline - System.nanoTime() <= 0) {
                        writeStarted.set(false);
                        return null;
                    }
                    try {
                        flushTimeout.set(timer.schedule(connection::close,
                                REQUEST_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS));
                    } catch (RuntimeException timerClosed) {
                        writeStarted.set(false);
                        throw new IOException("backend control service closed before response write", timerClosed);
                    }
                    return frame;
                }, () -> {
                    writeStarted.set(false);
                    ScheduledFuture<?> timeout = flushTimeout.getAndSet(null);
                    if (timeout != null) timeout.cancel(false);
                });
            }
            catch (IOException ignored) { connection.close(); }
            finally {
                ScheduledFuture<?> timeout = flushTimeout.getAndSet(null);
                if (timeout != null) timeout.cancel(false);
                releaseWrite(connection, frame.length);
            }
        });
    }

    private boolean reserveWrite(Connection connection, int size) {
        if (!writeTasks.tryAcquire()) return false;
        if (!connection.reserve(size)) {
            writeTasks.release();
            return false;
        }
        for (;;) {
            long used = globalQueuedBytes.get();
            if (used + size > MAX_GLOBAL_QUEUED_BYTES) {
                connection.release(size);
                writeTasks.release();
                return false;
            }
            if (globalQueuedBytes.compareAndSet(used, used + size)) return true;
        }
    }

    private void releaseWrite(Connection connection, int size) {
        connection.release(size);
        globalQueuedBytes.addAndGet(-size);
        writeTasks.release();
    }

    private static byte[] errorFrame(long operationId, Throwable failure) {
        try { return MessageCodec.error(operationId, MessagingException.Code.PROTOCOL_ERROR, safeDetail(failure)); }
        catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    private static byte[] singleByteFrame(int type) {
        return new byte[]{(byte) type};
    }

    private static <T, R> CompletionStage<R> mapCancellable(CompletionStage<T> source,
                                                             java.util.function.Function<T, R> mapper) {
        CompletableFuture<T> sourceFuture = source.toCompletableFuture();
        CompletableFuture<R> mapped = new CompletableFuture<>();
        source.whenComplete((value, failure) -> {
            if (failure != null) mapped.completeExceptionally(unwrap(failure));
            else {
                try { mapped.complete(mapper.apply(value)); }
                catch (Throwable mappingFailure) { mapped.completeExceptionally(mappingFailure); }
            }
        });
        mapped.whenComplete((ignored, failure) -> {
            if (mapped.isCancelled()) sourceFuture.cancel(false);
        });
        return mapped;
    }

    private static Message retarget(Message message, Endpoint target) {
        return new Message(message.id(), message.kind(), message.channel(), message.source(), target,
                message.replyTo(), message.payload());
    }

    private List<Connection> connectedBackends() {
        synchronized (lock) {
            return byBackend.values().stream().map(lease -> lease.connection)
                    .filter(Objects::nonNull).filter(connection -> connection.live.get())
                    .sorted(java.util.Comparator.comparing(connection -> connection.name)).toList();
        }
    }

    private static String namespace(String channel) { return channel.substring(0, channel.indexOf(':')); }

    private static long deadlineAfterMillis(long timeoutMillis) {
        long now = System.nanoTime();
        long nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        return now > Long.MAX_VALUE - nanos ? Long.MAX_VALUE : now + nanos;
    }

    private static Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        return nanos <= 0 ? Duration.ZERO : Duration.ofNanos(nanos);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static MessagingException asMessagingException(Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof MessagingException messagingFailure) return messagingFailure;
        return new MessagingException(MessagingException.Code.HANDLER_FAILED,
                "message delivery failed", cause);
    }

    private static SendResult sendFailure(Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof MessagingException messagingFailure) {
            return switch (messagingFailure.code()) {
                case BACKPRESSURED -> SendResult.BACKPRESSURED;
                case NOT_CONNECTED, CLOSED -> SendResult.NOT_CONNECTED;
                case TIMED_OUT -> SendResult.TIMED_OUT;
                case NO_HANDLER -> SendResult.NO_SUBSCRIBER;
                case REJECTED -> SendResult.REJECTED;
                case HANDLER_FAILED, PROTOCOL_ERROR -> SendResult.FAILED;
            };
        }
        return SendResult.FAILED;
    }

    private static String safeDetail(Throwable failure) {
        String detail = failure.getMessage();
        return detail == null ? failure.getClass().getSimpleName() : detail;
    }

    private static <T> CompletionStage<T> failed(Throwable failure) {
        return CompletableFuture.failedFuture(failure);
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> Thread.ofVirtual().name(prefix + "-" + sequence.incrementAndGet()).unstarted(task);
    }

    private Connection current(String name) {
        synchronized (lock) {
            Lease lease = byBackend.get(name);
            return lease == null || lease.connection == null || !lease.connection.live.get()
                    ? null : lease.connection;
        }
    }

    private static Frame readFrame(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > MAX_FRAME) throw new IOException("invalid backend control frame length");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException();
        DataInputStream body = new DataInputStream(new ByteArrayInputStream(bytes));
        return new Frame(body.readUnsignedByte(), body, bytes);
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
        routingWorkers.shutdownNow();
        synchronized (lock) {
            for (Lease lease : java.util.List.copyOf(byInstance.values())) removeLease(lease);
        }
    }

    private record Registration(String instanceId, String name, String generation, URI address,
                                java.util.Set<String> allowedNamespaces,
                                java.util.Set<String> allowedReceiveNamespaces) { }
    private record Frame(int type, DataInputStream input, byte[] bytes) { }
    private interface Writer { void write(DataOutputStream output) throws IOException; }
    private interface FrameSupplier { byte[] get() throws IOException; }

    private static final class PendingOperation {
        private final CompletableFuture<MessageCodec.Response> result;
        private final MessageCodec.Response.Type expected;
        private PendingOperation(CompletableFuture<MessageCodec.Response> result,
                                 MessageCodec.Response.Type expected) {
            this.result = result;
            this.expected = expected;
        }
    }

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
        private final ConcurrentHashMap<Long, PendingOperation> pending = new ConcurrentHashMap<>();
        private final AtomicInteger inboundRequests = new AtomicInteger();
        private final Semaphore requestSlots = new Semaphore(MAX_REQUESTS);
        private String instanceId, name;
        private java.util.Set<String> allowedNamespaces = java.util.Set.of();
        private java.util.Set<String> allowedReceiveNamespaces = java.util.Set.of();
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
        private boolean writeFrame(FrameSupplier frameSupplier, Runnable afterWrite) throws IOException {
            synchronized (writeLock) {
                if (!live.get()) throw new SocketException("backend control disconnected");
                byte[] frame = frameSupplier.get();
                if (frame == null) return false;
                if (frame.length < 1 || frame.length > MAX_FRAME)
                    throw new IOException("control frame length out of bounds");
                output.writeInt(frame.length);
                output.write(frame);
                output.flush();
                afterWrite.run();
                return true;
            }
        }
        private void close() {
            if (!live.compareAndSet(true, false)) return;
            try { socket.close(); } catch (IOException ignored) { }
            for (PendingOperation operation : pending.values())
                operation.result.completeExceptionally(new MessagingException(MessagingException.Code.NOT_CONNECTED,
                        "backend control disconnected"));
            pending.clear();
        }
    }
}

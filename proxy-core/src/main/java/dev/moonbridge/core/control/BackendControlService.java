package dev.moonbridge.core.control;

import dev.moonbridge.app.ProxyConfiguration;
import dev.moonbridge.core.backend.BackendCatalog;
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

import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import static dev.moonbridge.core.control.ControlFailures.*;
import static dev.moonbridge.core.control.ControlMessages.*;
import static dev.moonbridge.core.control.ControlProtocol.*;

/** Independent authenticated backend connection for registration and plugin control messages. */
public final class BackendControlService implements BackendChannelTransport, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackendControlService.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final ProxyConfiguration.BackendChannel configuration;
    private final UUID proxyEpoch;
    private final Semaphore connectionSlots;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("moonbridge-control-timer", 0).factory());
    private final SecureRandom random = new SecureRandom();
    private final ThreadPoolExecutor routingWorkers = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(512), namedFactory("moonbridge-control-routing"),
            new ThreadPoolExecutor.AbortPolicy());
    private final RegistrationVerifier verifier;
    private final LeaseRegistry leases;
    private final OutboundWriter writer = new OutboundWriter(timer);
    private final BackendExchange exchange = new BackendExchange(timer, writer, routingWorkers);
    private final InboundRouter router;
    private volatile boolean closed;
    private ServerSocket listener;

    public BackendControlService(ProxyConfiguration.BackendChannel configuration, BackendCatalog catalog,
                                 LocalMessaging messaging) {
        this(configuration, catalog, messaging, UUID.randomUUID());
    }

    public BackendControlService(ProxyConfiguration.BackendChannel configuration, BackendCatalog catalog,
                                 LocalMessaging messaging, UUID proxyEpoch) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(messaging, "messaging");
        this.proxyEpoch = Objects.requireNonNull(proxyEpoch, "proxyEpoch");
        this.connectionSlots = new Semaphore(configuration.maxConnections());
        this.verifier = new RegistrationVerifier(configuration);
        this.leases = new LeaseRegistry(configuration, catalog, () -> closed);
        this.router = new InboundRouter(messaging, leases, exchange, writer);
    }

    public synchronized void start() throws IOException {
        if (listener != null || closed) throw new IllegalStateException("control listener already started or closed");
        ServerSocket server = new ServerSocket();
        try {
            server.bind(configuration.listenAddress(), 32);
            listener = server;
            timer.scheduleAtFixedRate(leases::expireLeases, 1, 1, TimeUnit.SECONDS);
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
            if (register.type() != REGISTER) throw new IOException("expected backend registration");
            RegistrationVerifier.Registration requested = verifier.parse(register.input(), nonce);
            leases.register(connection, requested);
            connection.write(REGISTERED, out -> {
                out.writeLong(connection.epoch);
                out.writeLong(proxyEpoch.getMostSignificantBits());
                out.writeLong(proxyEpoch.getLeastSignificantBits());
            });
            socket.setSoTimeout(configuration.leaseSeconds() * 1_000);
            while (!closed && connection.live.get()) {
                Frame frame = readFrame(connection.input);
                if (!leases.isCurrent(connection)) break;
                switch (frame.type()) {
                    case HEARTBEAT -> {
                        requireEmpty(frame.input());
                        leases.renew(connection);
                        writer.writeAsync(connection, singleByteFrame(PONG));
                    }
                    case GOODBYE -> {
                        requireEmpty(frame.input());
                        leases.unregister(connection);
                        return;
                    }
                    case MessageCodec.MESSAGE -> router.receiveMessage(connection, frame.bytes());
                    case MessageCodec.RESPONSE -> exchange.receiveResponse(connection, frame.bytes());
                    default -> throw new IOException("unexpected backend control frame " + frame.type());
                }
            }
        } catch (EOFException | SocketException expected) {
            // A connection loss retains registration until its lease expires.
        } catch (Exception failure) {
            if (!closed) LOGGER.debug("Backend control connection ended: {}", failure.toString());
        } finally {
            leases.disconnect(connection);
        }
    }

    @Override public CompletionStage<SendResult> send(Message message) {
        Objects.requireNonNull(message, "message");
        if (!Endpoint.proxy().equals(message.source()) || message.kind() != MessageKind.EVENT
                || message.target() == null || message.target().isProxy()) {
            return failed(new MessagingException(MessagingException.Code.REJECTED,
                    "proxy send must be an event targeted at a backend"));
        }
        Connection connection = leases.current(message.target().backendName());
        if (connection == null) return CompletableFuture.completedFuture(SendResult.NOT_CONNECTED);
        if (!connection.allowedReceiveNamespaces.contains(namespace(message.channel())))
            return CompletableFuture.completedFuture(SendResult.REJECTED);
        return exchange.sendTo(connection, message, REQUEST_TIMEOUT);
    }

    @Override public CompletionStage<Message> request(Message message, Duration timeout) {
        Objects.requireNonNull(message, "message");
        if (!Endpoint.proxy().equals(message.source()) || message.kind() != MessageKind.REQUEST
                || message.target() == null || message.target().isProxy()) {
            return failed(new MessagingException(MessagingException.Code.REJECTED,
                    "proxy request must target a backend"));
        }
        Connection connection = leases.current(message.target().backendName());
        if (connection == null) return failed(new MessagingException(
                MessagingException.Code.NOT_CONNECTED, "target backend is not connected"));
        if (!connection.allowedReceiveNamespaces.contains(namespace(message.channel())))
            return failed(new MessagingException(MessagingException.Code.REJECTED,
                    "target backend does not allow this message namespace"));
        return exchange.requestFrom(connection, message, timeout);
    }

    @Override public CompletionStage<PublishResult> publish(Message event) {
        Objects.requireNonNull(event, "event");
        if (!Endpoint.proxy().equals(event.source()) || event.kind() != MessageKind.EVENT || event.target() != null)
            return failed(new MessagingException(MessagingException.Code.REJECTED,
                    "proxy publish must be an untargeted event"));
        List<Connection> destinations = leases.connectedBackends().stream()
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
            CompletionStage<SendResult> delivery = exchange.sendTo(destination, retarget(event, endpoint), REQUEST_TIMEOUT);
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

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> Thread.ofVirtual().name(prefix + "-" + sequence.incrementAndGet()).unstarted(task);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (listener != null) {
            try { listener.close(); } catch (IOException ignored) { }
        }
        timer.shutdownNow();
        routingWorkers.shutdownNow();
        leases.closeAll();
    }
}

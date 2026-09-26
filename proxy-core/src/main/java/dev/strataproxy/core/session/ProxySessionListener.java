package dev.strataproxy.core.session;

import dev.strataproxy.api.PlacementDecision;
import dev.strataproxy.api.ConnectionCheck;
import dev.strataproxy.api.LoginCheck;
import dev.strataproxy.api.PlayerIdentity;
import dev.strataproxy.api.PlayerView;
import dev.strataproxy.api.Players;
import dev.strataproxy.api.TransferResult;
import dev.strataproxy.api.TransferStatus;
import dev.strataproxy.core.auth.MinecraftEncryptionRequest;
import dev.strataproxy.core.auth.SessionVerifier;
import dev.strataproxy.core.backend.BackendCatalog;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.dns.DnsAddressResolverGroup;
import io.netty.resolver.dns.DnsNameResolverBuilder;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.concurrent.ThreadFactory;

/** Owns protocol 5 client sessions from login through backend disconnect. */
public final class ProxySessionListener implements Players {
    @FunctionalInterface public interface CommandDispatcher {
        /** Returns true only if this proxy owns the command. */
        boolean dispatch(PlayerView player, String message, Consumer<String> reply, BooleanSupplier admission);
    }
    private final SocketAddress bindAddress;
    private final BackendCatalog catalog;
    private final SessionVerifier verifier;
    private final Duration loginStageTimeout;
    private final Duration placementTimeout;
    private final Duration transferCutoverTimeout;
    private final Duration initialPlayTimeout;
    private final KeyPair encryptionKeys;
    private final SecureRandom random = new SecureRandom();
    private final EventLoopGroup boss = new NioEventLoopGroup(1, namedFactory("strataproxy-session-accept"));
    private final EventLoopGroup workers = new NioEventLoopGroup(0, namedFactory("strataproxy-session-io"));
    private final AddressResolverGroup<InetSocketAddress> backendResolver;
    private final ConcurrentHashMap<UUID, Session> sessionsByPlayerId = new ConcurrentHashMap<>();
    private final Set<Session> allSessions = ConcurrentHashMap.newKeySet();
    private final AtomicLong nextConnectionId = new AtomicLong();
    private final AtomicInteger onlineCount = new AtomicInteger();
    private final AtomicInteger connectionCount = new AtomicInteger();
    private volatile Function<PlayerView, CompletionStage<Optional<PlacementDecision>>> placement;
    private volatile CommandDispatcher commandDispatcher;
    private ConnectionCheck connectionCheck;
    private LoginCheck loginCheck;
    private Duration accessTimeout = Duration.ofSeconds(5);
    private boolean accessConfigured;
    private int maxConnections = 4096;
    private volatile Channel listener;
    private volatile boolean closed;
    private boolean started;
    private volatile CompletableFuture<Void> shutdown;

    public ProxySessionListener(SocketAddress bindAddress, BackendCatalog catalog) {
        this(bindAddress, catalog, null, Duration.ofSeconds(15));
    }

    /** A non-null verifier enables online authentication and trusted legacy identity forwarding. */
    public ProxySessionListener(SocketAddress bindAddress, BackendCatalog catalog, SessionVerifier verifier) {
        this(bindAddress, catalog, verifier, Duration.ofSeconds(15));
    }

    public ProxySessionListener(SocketAddress bindAddress, BackendCatalog catalog,
                                SessionVerifier verifier, Duration placementTimeout) {
        this(bindAddress, catalog, verifier, Duration.ofSeconds(15), placementTimeout);
    }

    ProxySessionListener(SocketAddress bindAddress, BackendCatalog catalog, SessionVerifier verifier,
                         Duration loginStageTimeout, Duration placementTimeout) {
        this(bindAddress, catalog, verifier, loginStageTimeout, placementTimeout, Duration.ofSeconds(15));
    }

    ProxySessionListener(SocketAddress bindAddress, BackendCatalog catalog, SessionVerifier verifier,
                         Duration loginStageTimeout, Duration placementTimeout, Duration transferCutoverTimeout) {
        this(bindAddress, catalog, verifier, loginStageTimeout, placementTimeout, transferCutoverTimeout,
                Duration.ofMinutes(2));
    }

    ProxySessionListener(SocketAddress bindAddress, BackendCatalog catalog, SessionVerifier verifier,
                         Duration loginStageTimeout, Duration placementTimeout, Duration transferCutoverTimeout,
                         Duration initialPlayTimeout) {
        this(bindAddress, catalog, verifier, loginStageTimeout, placementTimeout, transferCutoverTimeout,
                initialPlayTimeout,
                new DnsAddressResolverGroup(new DnsNameResolverBuilder()
                        .datagramChannelType(NioDatagramChannel.class).queryTimeoutMillis(3000)));
    }

    ProxySessionListener(SocketAddress bindAddress, BackendCatalog catalog, SessionVerifier verifier,
                         Duration loginStageTimeout, Duration placementTimeout, Duration transferCutoverTimeout,
                         AddressResolverGroup<InetSocketAddress> backendResolver) {
        this(bindAddress, catalog, verifier, loginStageTimeout, placementTimeout, transferCutoverTimeout,
                Duration.ofMinutes(2), backendResolver);
    }

    ProxySessionListener(SocketAddress bindAddress, BackendCatalog catalog, SessionVerifier verifier,
                         Duration loginStageTimeout, Duration placementTimeout, Duration transferCutoverTimeout,
                         Duration initialPlayTimeout, AddressResolverGroup<InetSocketAddress> backendResolver) {
        this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.verifier = verifier;
        this.backendResolver = Objects.requireNonNull(backendResolver, "backendResolver");
        this.loginStageTimeout = Objects.requireNonNull(loginStageTimeout, "loginStageTimeout");
        this.placementTimeout = Objects.requireNonNull(placementTimeout, "placementTimeout");
        this.transferCutoverTimeout = Objects.requireNonNull(transferCutoverTimeout, "transferCutoverTimeout");
        this.initialPlayTimeout = Objects.requireNonNull(initialPlayTimeout, "initialPlayTimeout");
        if (loginStageTimeout.isZero() || loginStageTimeout.isNegative()
                || placementTimeout.isZero() || placementTimeout.isNegative()
                || transferCutoverTimeout.isZero() || transferCutoverTimeout.isNegative()
                || initialPlayTimeout.isZero() || initialPlayTimeout.isNegative()
                || loginStageTimeout.compareTo(Duration.ofMinutes(3)) > 0
                || placementTimeout.compareTo(Duration.ofMinutes(3)) > 0
                || transferCutoverTimeout.compareTo(Duration.ofMinutes(3)) > 0
                || initialPlayTimeout.compareTo(Duration.ofMinutes(3)) > 0) {
            throw new IllegalArgumentException("session timeouts must be within three minutes");
        }
        try {
            if (verifier == null) encryptionKeys = null;
            else {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(1024, random);
                encryptionKeys = generator.generateKeyPair();
            }
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Could not initialize Minecraft online authentication", failure);
        }
    }

    /** Sets the PluginHost::placeInitial callback exactly once, before start(). */
    public synchronized void setPlacement(
            Function<PlayerView, CompletionStage<Optional<PlacementDecision>>> placement) {
        if (started || closed || this.placement != null) {
            throw new IllegalStateException("Placement must be configured once before listener start");
        }
        this.placement = Objects.requireNonNull(placement, "placement");
    }

    /** Configures optional plugin command dispatch before the listener starts. */
    public synchronized void setCommandDispatcher(CommandDispatcher dispatcher) {
        if (started || closed || commandDispatcher != null) {
            throw new IllegalStateException("Command dispatcher must be configured once before listener start");
        }
        commandDispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    /** Bounds accepted sessions, including clients that have not completed login. */
    public synchronized void setMaxConnections(int limit) {
        if (started || closed) throw new IllegalStateException("Connection limit must be set before listener start");
        if (limit < 1 || limit > 1_000_000) throw new IllegalArgumentException("invalid connection limit");
        maxConnections = limit;
    }

    /** Installs host dispatchers before start; absent checks add no worker dispatch or gate. */
    public synchronized void setAccessChecks(ConnectionCheck connectionCheck, LoginCheck loginCheck, Duration timeout) {
        if (started || closed || accessConfigured) {
            throw new IllegalStateException("Access checks must be configured once before listener start");
        }
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(3)) > 0) {
            throw new IllegalArgumentException("access timeout must be positive and within three minutes");
        }
        this.connectionCheck = connectionCheck;
        this.loginCheck = loginCheck;
        this.accessTimeout = timeout;
        accessConfigured = true;
    }

    ConnectionCheck connectionCheck() { return connectionCheck; }
    LoginCheck loginCheck() { return loginCheck; }
    Duration accessTimeout() { return accessTimeout; }

    public synchronized CompletionStage<Channel> start() {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("listener is closed"));
        if (started) return CompletableFuture.failedFuture(new IllegalStateException("listener already started"));
        if (placement == null) return CompletableFuture.failedFuture(new IllegalStateException("placement callback is not configured"));
        started = true;
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(boss, workers)
                .channel(NioServerSocketChannel.class)
                .childOption(io.netty.channel.ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel channel) {
                        Session session = registerSession(channel);
                        if (session == null) return;
                        if (connectionCheck != null) {
                            channel.pipeline().addLast("connection-access", new ConnectionGate(connectionCheck,
                                    accessTimeout.plusSeconds(1), session::connectionAccepted, session::closePair));
                        }
                        channel.pipeline().addLast("minecraft-frame-decoder", new dev.strataproxy.core.protocol.MinecraftFrameDecoder(
                                dev.strataproxy.core.protocol.ProtocolProfile.minecraft1710(), true,
                                dev.strataproxy.core.protocol.ProtocolProfile.MAX_LOGIN_FRAME_BYTES));
                        channel.pipeline().addLast("minecraft-frame-encoder", new SessionFrameEncoder());
                        channel.pipeline().addLast("initial-session", session);
                    }
                });
        CompletableFuture<Channel> result = new CompletableFuture<>();
        ChannelFuture bind = bootstrap.bind(bindAddress);
        listener = bind.channel();
        bind.addListener(future -> {
            if (future.isSuccess()) {
                if (closed) {
                    bind.channel().close();
                    result.completeExceptionally(new IllegalStateException("listener closed during bind"));
                } else {
                    result.complete(listener);
                }
            } else {
                result.completeExceptionally(future.cause());
                close();
            }
        });
        return result;
    }

    /** Serializes admission with close() so every accepted session is either closed or rejected. */
    private synchronized Session registerSession(SocketChannel channel) {
        if (closed || connectionCount.get() >= maxConnections) {
            channel.close();
            return null;
        }
        Session session = new Session(this, channel);
        allSessions.add(session);
        connectionCount.incrementAndGet();
        return session;
    }

    @Override public Optional<PlayerView> find(PlayerIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        Session session = sessionsByPlayerId.get(identity.playerId());
        return session == null ? Optional.empty()
                : session.onlineView().filter(view -> view.identity().equals(identity));
    }

    @Override public List<PlayerView> online() {
        return sessionsByPlayerId.values().stream().flatMap(session -> session.onlineView().stream()).toList();
    }

    @Override public CompletionStage<TransferResult> transfer(PlayerIdentity identity, String backendName) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(backendName, "backendName");
        Session session = sessionsByPlayerId.get(identity.playerId());
        if (session == null || session.onlineView().filter(view -> view.identity().equals(identity)).isEmpty()) {
            return CompletableFuture.completedFuture(TransferResult.of(TransferStatus.PLAYER_NOT_CONNECTED));
        }
        return session.transferTo(backendName);
    }

    public synchronized CompletionStage<Void> close() {
        if (shutdown != null) return shutdown;
        closed = true;
        Channel current = listener;
        if (current != null) current.close();
        allSessions.forEach(Session::closePair);
        backendResolver.close();
        shutdown = new CompletableFuture<>();
        boss.shutdownGracefully().addListener(first -> workers.shutdownGracefully().addListener(second -> {
            if (first.isSuccess() && second.isSuccess()) shutdown.complete(null);
            else shutdown.completeExceptionally(first.isSuccess() ? second.cause() : first.cause());
        }));
        return shutdown;
    }

    AddressResolverGroup<InetSocketAddress> backendResolver() { return backendResolver; }

    private long nextConnectionId() {
        long id = nextConnectionId.getAndUpdate(value -> {
            if (value == Long.MAX_VALUE) throw new IllegalStateException("connection id exhausted");
            return value + 1;
        });
        return id;
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicLong number = new AtomicLong();
        return task -> {
            Thread thread = new Thread(task, prefix + "-" + number.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    BackendCatalog catalog() { return catalog; }
    boolean onlineMode() { return verifier != null; }
    SessionVerifier verifier() { return verifier; }
    Duration loginStageTimeout() { return loginStageTimeout; }
    Duration placementTimeout() { return placementTimeout; }
    Duration transferCutoverTimeout() { return transferCutoverTimeout; }
    Duration initialPlayTimeout() { return initialPlayTimeout; }
    KeyPair encryptionKeys() { return encryptionKeys; }
    MinecraftEncryptionRequest newEncryptionRequest() {
        return MinecraftEncryptionRequest.create("", encryptionKeys.getPublic(), random);
    }
    Function<PlayerView, CompletionStage<Optional<PlacementDecision>>> placement() { return placement; }
    CommandDispatcher commandDispatcher() { return commandDispatcher; }
    boolean claimIdentity(UUID uuid, Session session) { return sessionsByPlayerId.putIfAbsent(uuid, session) == null; }
    void releaseIdentity(UUID uuid, Session session) { sessionsByPlayerId.remove(uuid, session); }
    Set<Session> allSessions() { return allSessions; }
    long allocateConnectionId() { return nextConnectionId(); }
    int onlineCount() { return onlineCount.get(); }
    void sessionPublished() { onlineCount.incrementAndGet(); }
    void sessionUnpublished() { onlineCount.decrementAndGet(); }
    void sessionClosed() { connectionCount.decrementAndGet(); }
}

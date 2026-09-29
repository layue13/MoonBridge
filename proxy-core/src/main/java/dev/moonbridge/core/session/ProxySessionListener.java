package dev.moonbridge.core.session;

import dev.moonbridge.api.PlacementDecision;
import dev.moonbridge.api.event.Event;
import dev.moonbridge.api.event.ConnectionAdmissionEvent;
import dev.moonbridge.api.event.PlayerDisconnectedEvent;
import dev.moonbridge.api.event.ServerConnectedEvent;
import dev.moonbridge.core.event.EventDispatcher;
import dev.moonbridge.core.permission.PermissionService;
import dev.moonbridge.api.PlayerIdentity;
import dev.moonbridge.api.PlayerView;
import dev.moonbridge.api.MessageResult;
import dev.moonbridge.api.DisconnectResult;
import dev.moonbridge.api.Players;
import dev.moonbridge.api.TransferResult;
import dev.moonbridge.api.TransferStatus;
import dev.moonbridge.api.profile.ProfileHandoffCoordinator;
import dev.moonbridge.core.auth.MinecraftEncryptionRequest;
import dev.moonbridge.core.auth.SessionVerifier;
import dev.moonbridge.core.backend.BackendCatalog;
import dev.moonbridge.core.protocol.ServerListStatus;
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
import java.util.Map;
import net.kyori.adventure.text.Component;
import dev.moonbridge.core.protocol.MinecraftText;
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
        boolean dispatch(PlayerView player, String message, Consumer<Component> reply, BooleanSupplier admission);
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
    private final EventLoopGroup boss = new NioEventLoopGroup(1, namedFactory("moonbridge-session-accept"));
    private final EventLoopGroup workers = new NioEventLoopGroup(0, namedFactory("moonbridge-session-io"));
    private final AddressResolverGroup<InetSocketAddress> backendResolver;
    private final ConcurrentHashMap<UUID, Session> sessionsByPlayerId = new ConcurrentHashMap<>();
    private final Set<Session> allSessions = ConcurrentHashMap.newKeySet();
    private final AtomicLong nextConnectionId = new AtomicLong();
    private volatile UUID proxyEpoch = UUID.randomUUID();
    private volatile Map<String, byte[]> sessionBindingSecrets = Map.of();
    private final AtomicInteger onlineCount = new AtomicInteger();
    private final AtomicInteger connectionCount = new AtomicInteger();
    private volatile Function<PlayerView, CompletionStage<Optional<PlacementDecision>>> placement;
    private volatile boolean profileHandoffRequired;
    private volatile ProfileHandoffCoordinator profileHandoffCoordinator;
    private List<String> initialServers = List.of();
    private boolean initialServersConfigured;
    private volatile CommandDispatcher commandDispatcher;
    private Function<String, List<String>> commandNames;
    private java.util.function.BiFunction<PlayerView, String, List<String>> playerCommandNames;
    private java.util.function.BiPredicate<PlayerView, String> commandVisibility = (player, suggestion) -> true;
    private PermissionService permissions;
    private java.util.function.BiFunction<PlayerView, String, Optional<CompletionStage<List<String>>>> completions;

    private EventDispatcher events;
    private Duration eventTimeout = Duration.ofSeconds(5);
    private int maxConnections = 4096;
    private ServerListStatus serverListStatus = ServerListStatus.defaultStatus();
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

    /** Configures the host boot epoch and per-backend forwarding keys before accepting clients. */
    public synchronized void setSessionBinding(UUID proxyEpoch, Map<String, String> secretsByBackend) {
        if (started || closed || listener != null) {
            throw new IllegalStateException("Session binding must be configured before listener start");
        }
        Objects.requireNonNull(proxyEpoch, "proxyEpoch");
        Objects.requireNonNull(secretsByBackend, "secretsByBackend");
        Map<String, byte[]> copied = new java.util.HashMap<>();
        secretsByBackend.forEach((backend, secret) -> {
            if (backend == null || backend.isBlank() || secret == null
                    || secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
                throw new IllegalArgumentException("backend session binding keys require a backend and 32-byte secret");
            }
            copied.put(backend, secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        });
        sessionBindingSecrets.values().forEach(key -> java.util.Arrays.fill(key, (byte) 0));
        this.proxyEpoch = proxyEpoch;
        this.sessionBindingSecrets = Map.copyOf(copied);
    }

    UUID proxyEpoch() { return proxyEpoch; }

    byte[] sessionBindingSecret(String backendName) {
        byte[] secret = sessionBindingSecrets.get(backendName);
        return secret == null ? null : secret.clone();
    }

    /** Configures the explicit, ordered entry route used only when no plugin selects a route. */
    public synchronized void setInitialServers(List<String> servers) {
        if (started || closed || initialServersConfigured) {
            throw new IllegalStateException("Initial servers must be configured once before listener start");
        }
        Objects.requireNonNull(servers, "servers");
        initialServers = servers.isEmpty() ? List.of() : new PlacementDecision.Select(servers).backendNames();
        initialServersConfigured = true;
    }

    /** Enables fail-closed profile routing. A required but unavailable coordinator denies all routes. */
    public synchronized void setProfileHandoffs(boolean required, ProfileHandoffCoordinator coordinator) {
        if (started || closed) throw new IllegalStateException("Profile handoffs must be configured before listener start");
        if (required && coordinator == null)
            throw new IllegalArgumentException("required profile handoff needs a coordinator");
        this.profileHandoffRequired = required;
        this.profileHandoffCoordinator = coordinator;
    }

    /** Configures optional plugin command dispatch before the listener starts. */
    public synchronized void setCommandDispatcher(CommandDispatcher dispatcher) {
        if (started || closed || commandDispatcher != null) {
            throw new IllegalStateException("Command dispatcher must be configured once before listener start");
        }
        commandDispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    public synchronized void setCommandCompletion(Function<String, List<String>> names,
            java.util.function.BiFunction<PlayerView, String, Optional<CompletionStage<List<String>>>> handler) {
        if (started || closed || completions != null) throw new IllegalStateException("Completion must be configured before start");
        commandNames = Objects.requireNonNull(names);
        completions = Objects.requireNonNull(handler);
    }
    Function<String, List<String>> commandNames() { return commandNames; }
    /** Installs player-aware command visibility before accepting connections. */
    public synchronized void setPlayerCommandCompletion(
            java.util.function.BiFunction<PlayerView, String, List<String>> names,
            java.util.function.BiFunction<PlayerView, String, Optional<CompletionStage<List<String>>>> handler) {
        if (started || closed || completions != null) throw new IllegalStateException("Completion must be configured before start");
        playerCommandNames = Objects.requireNonNull(names);
        completions = Objects.requireNonNull(handler);
    }
    boolean hasCommandCompletion() { return completions != null; }
    /** Filters backend suggestions which collide with restricted proxy commands. */
    public synchronized void setCommandVisibility(java.util.function.BiPredicate<PlayerView, String> visibility) {
        if (started || closed) throw new IllegalStateException("Command visibility must be configured before start");
        commandVisibility = Objects.requireNonNull(visibility);
    }
    boolean commandVisible(PlayerView player, String suggestion) { return commandVisibility.test(player, suggestion); }
    List<String> commandNames(PlayerView player, String prefix) {
        return playerCommandNames == null ? commandNames.apply(prefix) : playerCommandNames.apply(player, prefix);
    }
    /** Installs the reliable permission lifecycle, independent of best-effort plugin notifications. */
    public synchronized void setPermissions(PermissionService service) {
        if (started || closed || permissions != null) throw new IllegalStateException("Permissions must be configured once before start");
        permissions = Objects.requireNonNull(service);
    }
    CompletionStage<Void> preparePermissions(PlayerView player) {
        return permissions == null ? CompletableFuture.completedFuture(null) : permissions.prepare(player);
    }
    void releasePermissions(PlayerIdentity identity) {
        if (permissions != null) permissions.release(identity);
    }
    Optional<CompletionStage<List<String>>> completeCommand(PlayerView player, String text) {
        return completions.apply(player, text);
    }

    /** Bounds accepted sessions, including clients that have not completed login. */
    public synchronized void setMaxConnections(int limit) {
        if (started || closed) throw new IllegalStateException("Connection limit must be set before listener start");
        if (limit < 1 || limit > 1_000_000) throw new IllegalArgumentException("invalid connection limit");
        maxConnections = limit;
    }

    /** Sets immutable server-list values before the listener starts. */
    public synchronized void setServerListStatus(ServerListStatus status) {
        if (started || closed) throw new IllegalStateException("Server list must be configured before listener start");
        serverListStatus = Objects.requireNonNull(status, "status");
    }

    ServerListStatus serverListStatus() { return serverListStatus; }

    /** Installs the plugin event runtime before start; events without subscribers skip dispatch. */
    public synchronized void setEvents(EventDispatcher events, Duration timeout) {
        if (started || closed || this.events != null) {
            throw new IllegalStateException("Events must be configured once before listener start");
        }
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(3)) > 0) {
            throw new IllegalArgumentException("event timeout must be positive and within three minutes");
        }
        this.events = Objects.requireNonNull(events, "events");
        this.eventTimeout = timeout;
    }

    boolean hasSubscribers(Class<?> eventType) { return events != null && events.hasSubscribers(eventType); }
    <R> CompletionStage<R> dispatchEvent(Event<R> event) { return events.dispatch(event); }
    void serverConnected(PlayerView player, Optional<String> previousServer) {
        if (permissions != null) permissions.update(player);
        if (hasSubscribers(ServerConnectedEvent.class)) events.dispatch(new ServerConnectedEvent(player, previousServer));
    }
    void playerDisconnected(PlayerView player) {
        if (hasSubscribers(PlayerDisconnectedEvent.class)) events.dispatch(new PlayerDisconnectedEvent(player));
    }
    Duration eventTimeout() { return eventTimeout; }

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
                        if (hasSubscribers(ConnectionAdmissionEvent.class)) {
                            channel.pipeline().addLast("connection-admission", new ConnectionGate(events,
                                    eventTimeout.plusSeconds(1), session::connectionAccepted, session::closePair));
                        }
                        channel.pipeline().addLast("minecraft-frame-decoder", new dev.moonbridge.core.protocol.MinecraftFrameDecoder(
                                dev.moonbridge.core.protocol.ProtocolProfile.minecraft1710(), true,
                                dev.moonbridge.core.protocol.ProtocolProfile.MAX_LOGIN_FRAME_BYTES));
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

    @Override public CompletionStage<MessageResult> sendMessage(PlayerIdentity identity, Component message) {
        Objects.requireNonNull(identity, "identity");
        String encoded = MinecraftText.encode(message);
        Session session = sessionsByPlayerId.get(identity.playerId());
        if (session == null || !session.matchesIdentity(identity)) {
            return CompletableFuture.completedFuture(MessageResult.NOT_CONNECTED);
        }
        return session.sendEncodedMessage(encoded);
    }

    @Override public CompletionStage<DisconnectResult> disconnect(PlayerIdentity identity, Component reason) {
        Objects.requireNonNull(identity, "identity");
        String encoded = MinecraftText.encodeReason(reason);
        Session session = sessionsByPlayerId.get(identity.playerId());
        if (session == null || !session.matchesIdentity(identity)) {
            return CompletableFuture.completedFuture(DisconnectResult.NOT_CONNECTED);
        }
        return session.disconnectEncoded(encoded);
    }

    public synchronized CompletionStage<Void> close() {
        if (shutdown != null) return shutdown;
        closed = true;
        sessionBindingSecrets.values().forEach(key -> java.util.Arrays.fill(key, (byte) 0));
        sessionBindingSecrets = Map.of();
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
    List<String> initialServers() { return initialServers; }
    Duration transferCutoverTimeout() { return transferCutoverTimeout; }
    Duration initialPlayTimeout() { return initialPlayTimeout; }
    boolean profileHandoffRequired() { return profileHandoffRequired; }
    ProfileHandoffCoordinator profileHandoffCoordinator() { return profileHandoffCoordinator; }
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

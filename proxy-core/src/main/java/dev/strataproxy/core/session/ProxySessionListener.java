package dev.strataproxy.core.session;

import dev.strataproxy.api.PlacementDecision;
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

import java.net.SocketAddress;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.concurrent.ThreadFactory;

/** Owns protocol 5 client sessions from login through backend disconnect. */
public final class ProxySessionListener implements Players {
    private final SocketAddress bindAddress;
    private final BackendCatalog catalog;
    private final SessionVerifier verifier;
    private final KeyPair encryptionKeys;
    private final SecureRandom random = new SecureRandom();
    private final EventLoopGroup boss = new NioEventLoopGroup(1, namedFactory("strataproxy-session-accept"));
    private final EventLoopGroup workers = new NioEventLoopGroup(0, namedFactory("strataproxy-session-io"));
    private final ConcurrentHashMap<PlayerIdentity, PlayerView> online = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PlayerIdentity, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Session> claimedIdentities = new ConcurrentHashMap<>();
    private final Set<Session> allSessions = ConcurrentHashMap.newKeySet();
    private final AtomicLong nextConnectionId = new AtomicLong();
    private volatile Function<PlayerView, CompletionStage<Optional<PlacementDecision>>> placement;
    private volatile Channel listener;
    private volatile boolean closed;
    private boolean started;
    private volatile CompletableFuture<Void> shutdown;

    public ProxySessionListener(SocketAddress bindAddress, BackendCatalog catalog) {
        this(bindAddress, catalog, null);
    }

    /** A non-null verifier enables online authentication and trusted legacy identity forwarding. */
    public ProxySessionListener(SocketAddress bindAddress, BackendCatalog catalog, SessionVerifier verifier) {
        this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.verifier = verifier;
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
                        Session session = new Session(ProxySessionListener.this, channel);
                        allSessions.add(session);
                        channel.pipeline().addLast("minecraft-frame-decoder", new dev.strataproxy.core.protocol.MinecraftFrameDecoder(
                                dev.strataproxy.core.protocol.ProtocolProfile.minecraft1710()));
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

    @Override public Optional<PlayerView> find(PlayerIdentity identity) {
        return Optional.ofNullable(online.get(Objects.requireNonNull(identity, "identity")));
    }

    @Override public List<PlayerView> online() { return List.copyOf(online.values()); }

    @Override public CompletionStage<TransferResult> transfer(PlayerIdentity identity, String backendName) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(backendName, "backendName");
        if (!online.containsKey(identity)) return CompletableFuture.completedFuture(TransferResult.of(TransferStatus.PLAYER_NOT_CONNECTED));
        return CompletableFuture.completedFuture(TransferResult.failed("backend transfer is not implemented"));
    }

    public synchronized CompletionStage<Void> close() {
        if (shutdown != null) return shutdown;
        closed = true;
        Channel current = listener;
        if (current != null) current.close();
        allSessions.forEach(Session::closePair);
        shutdown = new CompletableFuture<>();
        boss.shutdownGracefully().addListener(first -> workers.shutdownGracefully().addListener(second -> {
            if (first.isSuccess() && second.isSuccess()) shutdown.complete(null);
            else shutdown.completeExceptionally(first.isSuccess() ? second.cause() : first.cause());
        }));
        return shutdown;
    }

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
    KeyPair encryptionKeys() { return encryptionKeys; }
    MinecraftEncryptionRequest newEncryptionRequest() {
        return MinecraftEncryptionRequest.create("", encryptionKeys.getPublic(), random);
    }
    Function<PlayerView, CompletionStage<Optional<PlacementDecision>>> placement() { return placement; }
    ConcurrentHashMap<PlayerIdentity, PlayerView> onlineMap() { return online; }
    ConcurrentHashMap<PlayerIdentity, Session> sessions() { return sessions; }
    boolean claimIdentity(UUID uuid, Session session) { return claimedIdentities.putIfAbsent(uuid, session) == null; }
    void releaseIdentity(UUID uuid, Session session) { claimedIdentities.remove(uuid, session); }
    Set<Session> allSessions() { return allSessions; }
    long allocateConnectionId() { return nextConnectionId(); }
}

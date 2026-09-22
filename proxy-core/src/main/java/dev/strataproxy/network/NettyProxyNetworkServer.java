package dev.strataproxy.network;

import dev.strataproxy.compression.CompressionStrategy;
import dev.strataproxy.plugin.command.CommandRegistry;
import dev.strataproxy.plugin.event.EventBus;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.WriteBufferWaterMark;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Netty-based implementation of the Minecraft proxy frontend server.
 */
public final class NettyProxyNetworkServer implements ProxyNetworkServer {
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final NettyTransport transport;
    private final BackendResolver backendResolver;
    private final ProxyMetrics metrics;
    private final NetworkTuning tuning;
    private final CompressionRuntime compressionRuntime;
    private final MinecraftAuthRuntime authRuntime;
    private final MinecraftForwardingRuntime forwardingRuntime;
    private final MinecraftStatusRuntime statusRuntime;
    private final RelaySessionRegistry relaySessions;
    private final CommandRegistry commands;
    private final EventBus events;
    private final boolean compressionRewriteEnabled;
    private final int compressionRewriteMaxEventLoopDelayMillis;
    private final NetworkRuntimeMonitor runtimeMonitor;
    private final ConnectionAdmissionControl admissionControl;
    private final AtomicBoolean closed = new AtomicBoolean();
    private Channel channel;

    /**
     * Creates a server with default metrics, tuning, compression, and Java NIO transport.
     *
     * @param workerThreads requested worker thread count; zero lets Netty choose
     * @param backendResolver resolver used to select backend servers
     */
    public NettyProxyNetworkServer(int workerThreads, BackendResolver backendResolver) {
        this(workerThreads, backendResolver, new ProxyMetrics(), NetworkTuning.defaults(), false);
    }

    /**
     * Creates a server with a custom maximum frame length and otherwise default tuning.
     *
     * @param workerThreads requested worker thread count; zero lets Netty choose
     * @param backendResolver resolver used to select backend servers
     * @param metrics metrics accumulator
     * @param maxFrameLength maximum inbound Minecraft frame length
     */
    public NettyProxyNetworkServer(int workerThreads, BackendResolver backendResolver, ProxyMetrics metrics, int maxFrameLength) {
        this(workerThreads, backendResolver, metrics, new NetworkTuning(
                maxFrameLength,
                NetworkTuning.defaults().connectTimeoutMillis(),
                NetworkTuning.defaults().writeBufferLowBytes(),
                NetworkTuning.defaults().writeBufferHighBytes(),
                NetworkTuning.defaults().maxConnections(),
                NetworkTuning.defaults().maxConnectionsPerAddress(),
                NetworkTuning.defaults().maxNewConnectionsPerSecond(),
                NetworkTuning.defaults().maxNewConnectionsPerAddressPerSecond(),
                NetworkTuning.defaults().initialHandshakeTimeoutMillis(),
                NetworkTuning.defaults().proxyProtocol()),
                false);
    }

    /**
     * Creates a server with custom metrics and network tuning using Java NIO transport.
     *
     * @param workerThreads requested worker thread count; zero lets Netty choose
     * @param backendResolver resolver used to select backend servers
     * @param metrics metrics accumulator
     * @param tuning network limits and timeouts
     */
    public NettyProxyNetworkServer(int workerThreads, BackendResolver backendResolver, ProxyMetrics metrics, NetworkTuning tuning) {
        this(workerThreads, backendResolver, metrics, tuning, false);
    }

    /**
     * Creates a server with custom metrics, network tuning, and transport preference.
     *
     * @param workerThreads requested worker thread count; zero lets Netty choose
     * @param backendResolver resolver used to select backend servers
     * @param metrics metrics accumulator
     * @param tuning network limits and timeouts
     * @param nativeTransport whether native Netty transport should be preferred
     */
    public NettyProxyNetworkServer(int workerThreads, BackendResolver backendResolver, ProxyMetrics metrics, NetworkTuning tuning, boolean nativeTransport) {
        this(workerThreads, backendResolver, metrics, tuning, nativeTransport, CompressionRuntime.defaults());
    }

    /**
     * Creates a server with command and event services using default compression and offline authentication.
     *
     * @param workerThreads requested worker thread count; zero lets Netty choose
     * @param backendResolver resolver used to select backend servers
     * @param metrics metrics accumulator
     * @param tuning network limits and timeouts
     * @param commands command registry used by in-game command interception
     * @param events event bus used for player and proxy events
     */
    public NettyProxyNetworkServer(
            int workerThreads,
            BackendResolver backendResolver,
            ProxyMetrics metrics,
            NetworkTuning tuning,
            CommandRegistry commands,
            EventBus events) {
        this(workerThreads, backendResolver, metrics, tuning, false, CompressionRuntime.defaults(), null, MinecraftAuthRuntime.offline(), MinecraftForwardingRuntime.none(), MinecraftStatusRuntime.disabled(), false, 25, commands, events);
    }

    /**
     * Creates a server with custom compression strategy and default custom-payload policy.
     *
     * @param workerThreads requested worker thread count; zero lets Netty choose
     * @param backendResolver resolver used to select backend servers
     * @param metrics metrics accumulator
     * @param tuning network limits and timeouts
     * @param nativeTransport whether native Netty transport should be preferred
     * @param compressionStrategy compression strategy
     * @param compressionMinThreshold minimum compression threshold
     * @param compressionMaxThreshold maximum compression threshold
     * @param compressionCpuGuard CPU guard threshold for adaptive compression
     */
    public NettyProxyNetworkServer(
            int workerThreads,
            BackendResolver backendResolver,
            ProxyMetrics metrics,
            NetworkTuning tuning,
            boolean nativeTransport,
            CompressionStrategy compressionStrategy,
            int compressionMinThreshold,
            int compressionMaxThreshold,
            double compressionCpuGuard) {
        this(
                workerThreads,
                backendResolver,
                metrics,
                tuning,
                nativeTransport,
                compressionStrategy,
                compressionMinThreshold,
                compressionMaxThreshold,
                compressionCpuGuard,
                null);
    }

    /**
     * Creates a server with custom compression and custom-payload inspection policy.
     *
     * @param workerThreads requested worker thread count; zero lets Netty choose
     * @param backendResolver resolver used to select backend servers
     * @param metrics metrics accumulator
     * @param tuning network limits and timeouts
     * @param nativeTransport whether native Netty transport should be preferred
     * @param compressionStrategy compression strategy
     * @param compressionMinThreshold minimum compression threshold
     * @param compressionMaxThreshold maximum compression threshold
     * @param compressionCpuGuard CPU guard threshold for adaptive compression
     * @param customPayloadPolicy custom payload anomaly policy
     */
    public NettyProxyNetworkServer(
            int workerThreads,
            BackendResolver backendResolver,
            ProxyMetrics metrics,
            NetworkTuning tuning,
            boolean nativeTransport,
            CompressionStrategy compressionStrategy,
            int compressionMinThreshold,
            int compressionMaxThreshold,
            double compressionCpuGuard,
            Object customPayloadPolicy) {
        this(
                workerThreads,
                backendResolver,
                metrics,
                tuning,
                nativeTransport,
                compressionStrategy,
                compressionMinThreshold,
                compressionMaxThreshold,
                compressionCpuGuard,
                customPayloadPolicy,
                false);
    }

    /**
     * Creates a server with optional compressed-frame rewriting.
     *
     * @param workerThreads requested worker thread count; zero lets Netty choose
     * @param backendResolver resolver used to select backend servers
     * @param metrics metrics accumulator
     * @param tuning network limits and timeouts
     * @param nativeTransport whether native Netty transport should be preferred
     * @param compressionStrategy compression strategy
     * @param compressionMinThreshold minimum compression threshold
     * @param compressionMaxThreshold maximum compression threshold
     * @param compressionCpuGuard CPU guard threshold for adaptive compression
     * @param customPayloadPolicy custom payload anomaly policy
     * @param compressionRewriteEnabled whether compressed-frame rewrite is enabled
     */
    public NettyProxyNetworkServer(
            int workerThreads,
            BackendResolver backendResolver,
            ProxyMetrics metrics,
            NetworkTuning tuning,
            boolean nativeTransport,
            CompressionStrategy compressionStrategy,
            int compressionMinThreshold,
            int compressionMaxThreshold,
            double compressionCpuGuard,
            Object customPayloadPolicy,
            boolean compressionRewriteEnabled) {
        this(
                workerThreads,
                backendResolver,
                metrics,
                tuning,
                nativeTransport,
                compressionStrategy,
                compressionMinThreshold,
                compressionMaxThreshold,
                compressionCpuGuard,
                customPayloadPolicy,
                MinecraftAuthRuntime.offline(),
                compressionRewriteEnabled,
                25);
    }

    /**
     * Creates a server with optional compressed-frame rewriting and explicit rewrite latency guard.
     *
     * @param workerThreads requested worker thread count; zero lets Netty choose
     * @param backendResolver resolver used to select backend servers
     * @param metrics metrics accumulator
     * @param tuning network limits and timeouts
     * @param nativeTransport whether native Netty transport should be preferred
     * @param compressionStrategy compression strategy
     * @param compressionMinThreshold minimum compression threshold
     * @param compressionMaxThreshold maximum compression threshold
     * @param compressionCpuGuard CPU guard threshold for adaptive compression
     * @param customPayloadPolicy custom payload anomaly policy
     * @param compressionRewriteEnabled whether compressed-frame rewrite is enabled
     * @param compressionRewriteMaxEventLoopDelayMillis maximum event-loop delay allowed before rewrite suppression
     */
    public NettyProxyNetworkServer(
            int workerThreads,
            BackendResolver backendResolver,
            ProxyMetrics metrics,
            NetworkTuning tuning,
            boolean nativeTransport,
            CompressionStrategy compressionStrategy,
            int compressionMinThreshold,
            int compressionMaxThreshold,
            double compressionCpuGuard,
            Object customPayloadPolicy,
            boolean compressionRewriteEnabled,
            int compressionRewriteMaxEventLoopDelayMillis) {
        this(
                workerThreads,
                backendResolver,
                metrics,
                tuning,
                nativeTransport,
                compressionStrategy,
                compressionMinThreshold,
                compressionMaxThreshold,
                compressionCpuGuard,
                customPayloadPolicy,
                MinecraftAuthRuntime.offline(),
                compressionRewriteEnabled,
                compressionRewriteMaxEventLoopDelayMillis);
    }

    /**
     * Creates a server with custom authentication runtime.
     *
     * @param workerThreads requested worker thread count; zero lets Netty choose
     * @param backendResolver resolver used to select backend servers
     * @param metrics metrics accumulator
     * @param tuning network limits and timeouts
     * @param nativeTransport whether native Netty transport should be preferred
     * @param compressionStrategy compression strategy
     * @param compressionMinThreshold minimum compression threshold
     * @param compressionMaxThreshold maximum compression threshold
     * @param compressionCpuGuard CPU guard threshold for adaptive compression
     * @param customPayloadPolicy custom payload anomaly policy
     * @param authRuntime login authentication runtime
     * @param compressionRewriteEnabled whether compressed-frame rewrite is enabled
     * @param compressionRewriteMaxEventLoopDelayMillis maximum event-loop delay allowed before rewrite suppression
     */
    public NettyProxyNetworkServer(
            int workerThreads,
            BackendResolver backendResolver,
            ProxyMetrics metrics,
            NetworkTuning tuning,
            boolean nativeTransport,
            CompressionStrategy compressionStrategy,
            int compressionMinThreshold,
            int compressionMaxThreshold,
            double compressionCpuGuard,
            Object customPayloadPolicy,
            MinecraftAuthRuntime authRuntime,
            boolean compressionRewriteEnabled,
            int compressionRewriteMaxEventLoopDelayMillis) {
        this(
                workerThreads,
                backendResolver,
                metrics,
                tuning,
                nativeTransport,
                compressionStrategy,
                compressionMinThreshold,
                compressionMaxThreshold,
                compressionCpuGuard,
                customPayloadPolicy,
                authRuntime,
                MinecraftForwardingRuntime.none(),
                MinecraftStatusRuntime.disabled(),
                compressionRewriteEnabled,
                compressionRewriteMaxEventLoopDelayMillis);
    }

    /**
     * Creates a server with custom authentication and backend forwarding runtime.
     *
     * @param workerThreads requested worker thread count; zero lets Netty choose
     * @param backendResolver resolver used to select backend servers
     * @param metrics metrics accumulator
     * @param tuning network limits and timeouts
     * @param nativeTransport whether native Netty transport should be preferred
     * @param compressionStrategy compression strategy
     * @param compressionMinThreshold minimum compression threshold
     * @param compressionMaxThreshold maximum compression threshold
     * @param compressionCpuGuard CPU guard threshold for adaptive compression
     * @param customPayloadPolicy custom payload anomaly policy
     * @param authRuntime login authentication runtime
     * @param forwardingRuntime backend forwarding runtime
     * @param compressionRewriteEnabled whether compressed-frame rewrite is enabled
     * @param compressionRewriteMaxEventLoopDelayMillis maximum event-loop delay allowed before rewrite suppression
     */
    public NettyProxyNetworkServer(
            int workerThreads,
            BackendResolver backendResolver,
            ProxyMetrics metrics,
            NetworkTuning tuning,
            boolean nativeTransport,
            CompressionStrategy compressionStrategy,
            int compressionMinThreshold,
            int compressionMaxThreshold,
            double compressionCpuGuard,
            Object customPayloadPolicy,
            MinecraftAuthRuntime authRuntime,
            MinecraftForwardingRuntime forwardingRuntime,
            boolean compressionRewriteEnabled,
            int compressionRewriteMaxEventLoopDelayMillis) {
        this(
                workerThreads,
                backendResolver,
                metrics,
                tuning,
                nativeTransport,
                compressionStrategy,
                compressionMinThreshold,
                compressionMaxThreshold,
                compressionCpuGuard,
                customPayloadPolicy,
                authRuntime,
                forwardingRuntime,
                MinecraftStatusRuntime.disabled(),
                compressionRewriteEnabled,
                compressionRewriteMaxEventLoopDelayMillis);
    }

    /**
     * Creates a server with custom authentication, forwarding, and status response runtime.
     *
     * @param workerThreads requested worker thread count; zero lets Netty choose
     * @param backendResolver resolver used to select backend servers
     * @param metrics metrics accumulator
     * @param tuning network limits and timeouts
     * @param nativeTransport whether native Netty transport should be preferred
     * @param compressionStrategy compression strategy
     * @param compressionMinThreshold minimum compression threshold
     * @param compressionMaxThreshold maximum compression threshold
     * @param compressionCpuGuard CPU guard threshold for adaptive compression
     * @param customPayloadPolicy custom payload anomaly policy
     * @param authRuntime login authentication runtime
     * @param forwardingRuntime backend forwarding runtime
     * @param statusRuntime Minecraft status response runtime
     * @param compressionRewriteEnabled whether compressed-frame rewrite is enabled
     * @param compressionRewriteMaxEventLoopDelayMillis maximum event-loop delay allowed before rewrite suppression
     */
    public NettyProxyNetworkServer(
            int workerThreads,
            BackendResolver backendResolver,
            ProxyMetrics metrics,
            NetworkTuning tuning,
            boolean nativeTransport,
            CompressionStrategy compressionStrategy,
            int compressionMinThreshold,
            int compressionMaxThreshold,
            double compressionCpuGuard,
            Object customPayloadPolicy,
            MinecraftAuthRuntime authRuntime,
            MinecraftForwardingRuntime forwardingRuntime,
            MinecraftStatusRuntime statusRuntime,
            boolean compressionRewriteEnabled,
            int compressionRewriteMaxEventLoopDelayMillis) {
        this(workerThreads, backendResolver, metrics, tuning, nativeTransport, new CompressionRuntime(
                compressionStrategy,
                compressionMinThreshold,
                compressionMaxThreshold,
                compressionCpuGuard),
                customPayloadPolicy,
                authRuntime,
                forwardingRuntime,
                statusRuntime,
                compressionRewriteEnabled,
                compressionRewriteMaxEventLoopDelayMillis,
                null,
                null);
    }

    /**
     * Creates a server with the full public runtime surface, including command and event services.
     *
     * @param workerThreads requested worker thread count; zero lets Netty choose
     * @param backendResolver resolver used to select backend servers
     * @param metrics metrics accumulator
     * @param tuning network limits and timeouts
     * @param nativeTransport whether native Netty transport should be preferred
     * @param compressionStrategy compression strategy
     * @param compressionMinThreshold minimum compression threshold
     * @param compressionMaxThreshold maximum compression threshold
     * @param compressionCpuGuard CPU guard threshold for adaptive compression
     * @param customPayloadPolicy custom payload anomaly policy
     * @param authRuntime login authentication runtime
     * @param forwardingRuntime backend forwarding runtime
     * @param statusRuntime Minecraft status response runtime
     * @param compressionRewriteEnabled whether compressed-frame rewrite is enabled
     * @param compressionRewriteMaxEventLoopDelayMillis maximum event-loop delay allowed before rewrite suppression
     * @param commands command registry used by in-game command interception
     * @param events event bus used for player and proxy events
     */
    public NettyProxyNetworkServer(
            int workerThreads,
            BackendResolver backendResolver,
            ProxyMetrics metrics,
            NetworkTuning tuning,
            boolean nativeTransport,
            CompressionStrategy compressionStrategy,
            int compressionMinThreshold,
            int compressionMaxThreshold,
            double compressionCpuGuard,
            Object customPayloadPolicy,
            MinecraftAuthRuntime authRuntime,
            MinecraftForwardingRuntime forwardingRuntime,
            MinecraftStatusRuntime statusRuntime,
            boolean compressionRewriteEnabled,
            int compressionRewriteMaxEventLoopDelayMillis,
            CommandRegistry commands,
            EventBus events) {
        this(workerThreads, backendResolver, metrics, tuning, nativeTransport, new CompressionRuntime(
                compressionStrategy,
                compressionMinThreshold,
                compressionMaxThreshold,
                compressionCpuGuard),
                customPayloadPolicy,
                authRuntime,
                forwardingRuntime,
                statusRuntime,
                compressionRewriteEnabled,
                compressionRewriteMaxEventLoopDelayMillis,
                commands,
                events);
    }

    private NettyProxyNetworkServer(
            int workerThreads,
            BackendResolver backendResolver,
            ProxyMetrics metrics,
            NetworkTuning tuning,
            boolean nativeTransport,
            CompressionRuntime compressionRuntime) {
        this(workerThreads, backendResolver, metrics, tuning, nativeTransport, compressionRuntime, null);
    }

    private NettyProxyNetworkServer(
            int workerThreads,
            BackendResolver backendResolver,
            ProxyMetrics metrics,
            NetworkTuning tuning,
            boolean nativeTransport,
            CompressionRuntime compressionRuntime,
            Object customPayloadPolicy) {
        this(workerThreads, backendResolver, metrics, tuning, nativeTransport, compressionRuntime, customPayloadPolicy, MinecraftAuthRuntime.offline(), MinecraftForwardingRuntime.none(), MinecraftStatusRuntime.disabled(), false, 25, null, null);
    }

    private NettyProxyNetworkServer(
            int workerThreads,
            BackendResolver backendResolver,
            ProxyMetrics metrics,
            NetworkTuning tuning,
            boolean nativeTransport,
            CompressionRuntime compressionRuntime,
            Object customPayloadPolicy,
            boolean compressionRewriteEnabled) {
        this(workerThreads, backendResolver, metrics, tuning, nativeTransport, compressionRuntime, customPayloadPolicy, MinecraftAuthRuntime.offline(), MinecraftForwardingRuntime.none(), MinecraftStatusRuntime.disabled(), compressionRewriteEnabled, 25, null, null);
    }

    private NettyProxyNetworkServer(
            int workerThreads,
            BackendResolver backendResolver,
            ProxyMetrics metrics,
            NetworkTuning tuning,
            boolean nativeTransport,
            CompressionRuntime compressionRuntime,
            Object customPayloadPolicy,
            MinecraftAuthRuntime authRuntime,
            MinecraftForwardingRuntime forwardingRuntime,
            MinecraftStatusRuntime statusRuntime,
            boolean compressionRewriteEnabled,
            int compressionRewriteMaxEventLoopDelayMillis,
            CommandRegistry commands,
            EventBus events) {
        this.transport = NettyTransport.select(nativeTransport, workerThreads);
        this.bossGroup = transport.bossGroup();
        this.workerGroup = transport.workerGroup();
        this.backendResolver = backendResolver;
        this.metrics = metrics;
        this.metrics.networkTransport(transport.name(), transport.nativeTransport());
        this.tuning = tuning;
        this.compressionRuntime = compressionRuntime;
        this.authRuntime = authRuntime == null ? MinecraftAuthRuntime.offline() : authRuntime;
        this.forwardingRuntime = forwardingRuntime == null ? MinecraftForwardingRuntime.none() : forwardingRuntime;
        this.statusRuntime = statusRuntime == null ? MinecraftStatusRuntime.disabled() : statusRuntime;
        this.relaySessions = new RelaySessionRegistry();
        this.commands = commands;
        this.events = events;
        this.compressionRewriteEnabled = compressionRewriteEnabled;
        this.compressionRewriteMaxEventLoopDelayMillis = compressionRewriteMaxEventLoopDelayMillis;
        this.runtimeMonitor = new NetworkRuntimeMonitor(workerGroup, metrics, Duration.ofSeconds(1));
        this.admissionControl = new ConnectionAdmissionControl(
                tuning.maxConnections(),
                tuning.maxConnectionsPerAddress(),
                tuning.maxNewConnectionsPerSecond(),
                tuning.maxNewConnectionsPerAddressPerSecond());
    }

    @Override
    /** Provides bind. */
    public CompletionStage<Void> bind(InetSocketAddress address) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("server is closed"));
        }
        var future = new CompletableFuture<Void>();
        var bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(transport.serverChannel())
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.AUTO_READ, false)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(
                        tuning.writeBufferLowBytes(),
                        tuning.writeBufferHighBytes()))
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel channel) {
                        channel.config().setAutoRead(true);
                        if (tuning.proxyProtocol()) {
                            channel.pipeline().addLast("proxy-protocol-v1", new ProxyProtocolV1Handler());
                        }
                        channel.pipeline().addLast("connection-admission", new ConnectionAdmissionHandler(admissionControl, metrics, tuning.proxyProtocol()));
                        channel.pipeline().addLast("initial-handshake-timeout", new InitialHandshakeTimeoutHandler(
                                tuning.initialHandshakeTimeoutMillis(),
                                metrics));
                        channel.pipeline().addLast("initial-handshake-route", new InitialHandshakeRouteHandler(
                                backendResolver,
                                metrics,
                                tuning,
                                transport.clientChannel(),
                                compressionRuntime,
                                authRuntime,
                                forwardingRuntime,
                                statusRuntime,
                                compressionRewriteEnabled,
                                compressionRewriteMaxEventLoopDelayMillis,
                                relaySessions,
                                commands,
                                events));
                    }
                });

        bootstrap.bind(address).addListener(result -> {
            if (result.isSuccess()) {
                channel = ((io.netty.channel.ChannelFuture) result).channel();
                runtimeMonitor.start();
                future.complete(null);
            } else {
                close();
                future.completeExceptionally(result.cause());
            }
        });
        return future;
    }

    @Override
    /** Provides bind address. */
    public InetSocketAddress bindAddress() {
        if (channel == null) {
            throw new IllegalStateException("server is not bound");
        }
        return (InetSocketAddress) channel.localAddress();
    }

    /**
     * Returns the selected Netty transport name.
     *
     * @return transport name, such as {@code nio}, {@code epoll}, or {@code kqueue}
     */
    public String transportName() {
        return transport.name();
    }

    /**
     * Reports whether the server selected a native Netty transport.
     *
     * @return {@code true} when epoll or kqueue is active
     */
    public boolean nativeTransport() {
        return transport.nativeTransport();
    }

    /**
     * Requests a live player transfer between backend servers.
     *
     * @param playerName player name
     * @param targetServerName target backend server name
     * @return asynchronous transfer result
     */
    public CompletionStage<PlayerTransferResult> transferPlayer(String playerName, String targetServerName) {
        if (closed.get()) {
            return CompletableFuture.completedFuture(PlayerTransferResult.failure(
                    "server_closed",
                    playerName,
                    "",
                    targetServerName));
        }
        return relaySessions.transferPlayer(playerName, targetServerName);
    }

    /** Sends a plugin message to the current backend selected for one player. */
    public CompletionStage<dev.strataproxy.plugin.service.PluginMessageResult> sendPluginMessage(String playerName, String channel, byte[] payload) {
        if (closed.get()) return CompletableFuture.completedFuture(dev.strataproxy.plugin.service.PluginMessageResult.failure("server_closed"));
        return relaySessions.sendPluginMessage(playerName, channel, payload);
    }

    @Override
    /** Provides close. */
    public void close() {
        closeResources(!inEventLoop());
    }

    private void closeResources(boolean await) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (channel != null) {
            var closeFuture = channel.close();
            if (await) {
                closeFuture.awaitUninterruptibly();
            }
        }
        runtimeMonitor.close();
        var bossShutdown = bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        var workerShutdown = workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        if (await) {
            bossShutdown.awaitUninterruptibly();
            workerShutdown.awaitUninterruptibly();
        }
    }

    private boolean inEventLoop() {
        for (var executor : bossGroup) {
            if (executor.inEventLoop()) {
                return true;
            }
        }
        for (var executor : workerGroup) {
            if (executor.inEventLoop()) {
                return true;
            }
        }
        return false;
    }

    boolean shuttingDown() {
        return bossGroup.isShuttingDown() && workerGroup.isShuttingDown();
    }
}

package dev.strataproxy.network;

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

    /** Creates a listener from its complete, named configuration. */
    public NettyProxyNetworkServer(NettyProxyServerConfig config) {
        this.transport = NettyTransport.select(config.nativeTransport(), config.workerThreads());
        this.bossGroup = transport.bossGroup();
        this.workerGroup = transport.workerGroup();
        this.backendResolver = config.backendResolver();
        this.metrics = config.metrics();
        this.tuning = config.tuning();
        this.compressionRuntime = config.runtime().compressionRuntime();
        this.authRuntime = config.runtime().auth();
        this.forwardingRuntime = config.runtime().forwarding();
        this.statusRuntime = config.runtime().status();
        this.relaySessions = new RelaySessionRegistry();
        this.commands = config.runtime().commands();
        this.events = config.runtime().events();
        this.compressionRewriteEnabled = config.runtime().compressionRewriteEnabled();
        this.compressionRewriteMaxEventLoopDelayMillis = config.runtime().compressionRewriteMaxEventLoopDelayMillis();
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

    /** Requests a transfer for one exact relay connection. */
    public CompletionStage<PlayerTransferResult> transferPlayer(
            dev.strataproxy.plugin.service.PlayerIdentity player,
            String targetServerName) {
        if (closed.get()) {
            return CompletableFuture.completedFuture(PlayerTransferResult.failure(
                    "server_closed", "", "", targetServerName));
        }
        return relaySessions.transferPlayer(player, targetServerName);
    }

    /** Sends a plugin message to the current backend selected for one player. */
    public CompletionStage<dev.strataproxy.plugin.service.PluginMessageResult> sendPluginMessage(String playerName, String channel, byte[] payload) {
        if (closed.get()) return CompletableFuture.completedFuture(dev.strataproxy.plugin.service.PluginMessageResult.failure("server_closed"));
        return relaySessions.sendPluginMessage(playerName, channel, payload);
    }

    /** Sends a plugin message to one exact relay connection. */
    public CompletionStage<dev.strataproxy.plugin.service.PluginMessageResult> sendPluginMessage(
            dev.strataproxy.plugin.service.PlayerIdentity player,
            String channel,
            byte[] payload) {
        if (closed.get()) return CompletableFuture.completedFuture(dev.strataproxy.plugin.service.PluginMessageResult.failure("server_closed"));
        return relaySessions.sendPluginMessage(player, channel, payload);
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

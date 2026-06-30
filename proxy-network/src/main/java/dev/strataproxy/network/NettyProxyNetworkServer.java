package dev.strataproxy.network;

import dev.strataproxy.analysis.CustomPayloadAnomalyPolicy;
import dev.strataproxy.compression.CompressionStrategy;
import dev.strataproxy.observability.ProxyMetrics;
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

public final class NettyProxyNetworkServer implements ProxyNetworkServer {
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final NettyTransport transport;
    private final BackendResolver backendResolver;
    private final ProxyMetrics metrics;
    private final NetworkTuning tuning;
    private final CompressionRuntime compressionRuntime;
    private final CustomPayloadAnomalyPolicy customPayloadPolicy;
    private final MinecraftAuthRuntime authRuntime;
    private final MinecraftForwardingRuntime forwardingRuntime;
    private final MinecraftStatusRuntime statusRuntime;
    private final boolean compressionRewriteEnabled;
    private final int compressionRewriteMaxEventLoopDelayMillis;
    private final NetworkRuntimeMonitor runtimeMonitor;
    private final ConnectionAdmissionControl admissionControl;
    private final AtomicBoolean closed = new AtomicBoolean();
    private Channel channel;

    public NettyProxyNetworkServer(int workerThreads, BackendResolver backendResolver) {
        this(workerThreads, backendResolver, new ProxyMetrics(), NetworkTuning.defaults(), false);
    }

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

    public NettyProxyNetworkServer(int workerThreads, BackendResolver backendResolver, ProxyMetrics metrics, NetworkTuning tuning) {
        this(workerThreads, backendResolver, metrics, tuning, false);
    }

    public NettyProxyNetworkServer(int workerThreads, BackendResolver backendResolver, ProxyMetrics metrics, NetworkTuning tuning, boolean nativeTransport) {
        this(workerThreads, backendResolver, metrics, tuning, nativeTransport, CompressionRuntime.defaults());
    }

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
                CustomPayloadAnomalyPolicy.defaults());
    }

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
            CustomPayloadAnomalyPolicy customPayloadPolicy) {
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
            CustomPayloadAnomalyPolicy customPayloadPolicy,
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
            CustomPayloadAnomalyPolicy customPayloadPolicy,
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
            CustomPayloadAnomalyPolicy customPayloadPolicy,
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
            CustomPayloadAnomalyPolicy customPayloadPolicy,
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
            CustomPayloadAnomalyPolicy customPayloadPolicy,
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
                compressionRewriteMaxEventLoopDelayMillis);
    }

    private NettyProxyNetworkServer(
            int workerThreads,
            BackendResolver backendResolver,
            ProxyMetrics metrics,
            NetworkTuning tuning,
            boolean nativeTransport,
            CompressionRuntime compressionRuntime) {
        this(workerThreads, backendResolver, metrics, tuning, nativeTransport, compressionRuntime, CustomPayloadAnomalyPolicy.defaults());
    }

    private NettyProxyNetworkServer(
            int workerThreads,
            BackendResolver backendResolver,
            ProxyMetrics metrics,
            NetworkTuning tuning,
            boolean nativeTransport,
            CompressionRuntime compressionRuntime,
            CustomPayloadAnomalyPolicy customPayloadPolicy) {
        this(workerThreads, backendResolver, metrics, tuning, nativeTransport, compressionRuntime, customPayloadPolicy, MinecraftAuthRuntime.offline(), MinecraftForwardingRuntime.none(), MinecraftStatusRuntime.disabled(), false, 25);
    }

    private NettyProxyNetworkServer(
            int workerThreads,
            BackendResolver backendResolver,
            ProxyMetrics metrics,
            NetworkTuning tuning,
            boolean nativeTransport,
            CompressionRuntime compressionRuntime,
            CustomPayloadAnomalyPolicy customPayloadPolicy,
            boolean compressionRewriteEnabled) {
        this(workerThreads, backendResolver, metrics, tuning, nativeTransport, compressionRuntime, customPayloadPolicy, MinecraftAuthRuntime.offline(), MinecraftForwardingRuntime.none(), MinecraftStatusRuntime.disabled(), compressionRewriteEnabled, 25);
    }

    private NettyProxyNetworkServer(
            int workerThreads,
            BackendResolver backendResolver,
            ProxyMetrics metrics,
            NetworkTuning tuning,
            boolean nativeTransport,
            CompressionRuntime compressionRuntime,
            CustomPayloadAnomalyPolicy customPayloadPolicy,
            MinecraftAuthRuntime authRuntime,
            MinecraftForwardingRuntime forwardingRuntime,
            MinecraftStatusRuntime statusRuntime,
            boolean compressionRewriteEnabled,
            int compressionRewriteMaxEventLoopDelayMillis) {
        this.transport = NettyTransport.select(nativeTransport, workerThreads);
        this.bossGroup = transport.bossGroup();
        this.workerGroup = transport.workerGroup();
        this.backendResolver = backendResolver;
        this.metrics = metrics;
        this.metrics.networkTransport(transport.name(), transport.nativeTransport());
        this.tuning = tuning;
        this.compressionRuntime = compressionRuntime;
        this.customPayloadPolicy = customPayloadPolicy == null ? CustomPayloadAnomalyPolicy.defaults() : customPayloadPolicy;
        this.authRuntime = authRuntime == null ? MinecraftAuthRuntime.offline() : authRuntime;
        this.forwardingRuntime = forwardingRuntime == null ? MinecraftForwardingRuntime.none() : forwardingRuntime;
        this.statusRuntime = statusRuntime == null ? MinecraftStatusRuntime.disabled() : statusRuntime;
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
                                customPayloadPolicy,
                                authRuntime,
                                forwardingRuntime,
                                statusRuntime,
                                compressionRewriteEnabled,
                                compressionRewriteMaxEventLoopDelayMillis));
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
    public InetSocketAddress bindAddress() {
        if (channel == null) {
            throw new IllegalStateException("server is not bound");
        }
        return (InetSocketAddress) channel.localAddress();
    }

    public String transportName() {
        return transport.name();
    }

    public boolean nativeTransport() {
        return transport.nativeTransport();
    }

    @Override
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

package dev.strataproxy.network;

import dev.strataproxy.api.server.RegisteredServer;
import dev.strataproxy.observability.ProxyMetrics;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.WriteBufferWaterMark;

final class BackendConnector {
    private final ProxyMetrics metrics;
    private final NetworkTuning tuning;
    private final Class<? extends Channel> backendChannel;
    private final CompressionRuntime compressionRuntime;
    private final MinecraftForwardingRuntime forwardingRuntime;
    private final boolean compressionRewriteEnabled;
    private final int compressionRewriteMaxEventLoopDelayMillis;

    BackendConnector(
            ProxyMetrics metrics,
            NetworkTuning tuning,
            Class<? extends Channel> backendChannel,
            CompressionRuntime compressionRuntime,
            MinecraftForwardingRuntime forwardingRuntime,
            boolean compressionRewriteEnabled,
            int compressionRewriteMaxEventLoopDelayMillis) {
        this.metrics = metrics;
        this.tuning = tuning;
        this.backendChannel = backendChannel;
        this.compressionRuntime = compressionRuntime;
        this.forwardingRuntime = forwardingRuntime == null ? MinecraftForwardingRuntime.none() : forwardingRuntime;
        this.compressionRewriteEnabled = compressionRewriteEnabled;
        this.compressionRewriteMaxEventLoopDelayMillis = compressionRewriteMaxEventLoopDelayMillis;
    }

    ChannelFuture connect(
            Channel frontend,
            RegisteredServer selected,
            MinecraftCompressionAuditState compressionAudit,
            RelaySession session,
            BackendReplacementController replacementController) {
        var serverName = selected.descriptor().name();
        var identity = session.identity();
        var bootstrap = new Bootstrap()
                .group(frontend.eventLoop())
                .channel(backendChannel)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, tuning.connectTimeoutMillis())
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.AUTO_READ, false)
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(
                        tuning.writeBufferLowBytes(),
                        tuning.writeBufferHighBytes()))
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel backend) {
                        backend.pipeline().addLast("backend-relay", new BackendRelayHandler(
                                frontend,
                                metrics,
                                serverName,
                                tuning.maxFrameBytes(),
                                compressionAudit,
                                compressionRuntime,
                                identity,
                                forwardingRuntime,
                                compressionRewriteEnabled,
                                compressionRewriteMaxEventLoopDelayMillis,
                                replacementController));
                    }
                });
        return bootstrap.connect(selected.descriptor().address());
    }

    ChannelFuture connectForSwitchLogin(
            Channel frontend,
            RegisteredServer selected,
            MinecraftCompressionAuditState compressionAudit,
            RelaySession session,
            BackendSwitchLoginHandler.Listener listener) {
        var serverName = selected.descriptor().name();
        var bootstrap = new Bootstrap()
                .group(frontend.eventLoop())
                .channel(backendChannel)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, tuning.connectTimeoutMillis())
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.AUTO_READ, false)
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(
                        tuning.writeBufferLowBytes(),
                        tuning.writeBufferHighBytes()))
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel backend) {
                        backend.pipeline().addLast("backend-switch-login", new BackendSwitchLoginHandler(
                                metrics,
                                serverName,
                                tuning.maxFrameBytes(),
                                compressionAudit,
                                forwardingRuntime,
                                session.identity(),
                                listener));
                    }
                });
        return bootstrap.connect(selected.descriptor().address());
    }
}

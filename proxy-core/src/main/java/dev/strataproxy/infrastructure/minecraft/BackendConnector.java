package dev.strataproxy.infrastructure.minecraft;

import dev.strataproxy.domain.server.RegisteredServer;
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
    private final MinecraftProtocolProfile profile;

    BackendConnector(
            ProxyMetrics metrics,
            NetworkTuning tuning,
            Class<? extends Channel> backendChannel,
            CompressionRuntime compressionRuntime,
            MinecraftForwardingRuntime forwardingRuntime,
            boolean compressionRewriteEnabled,
            int compressionRewriteMaxEventLoopDelayMillis,
            int protocolVersion) {
        this(
                metrics,
                tuning,
                backendChannel,
                compressionRuntime,
                forwardingRuntime,
                compressionRewriteEnabled,
                compressionRewriteMaxEventLoopDelayMillis,
                MinecraftProtocolProfile.forVersion(protocolVersion));
    }

    BackendConnector(
            ProxyMetrics metrics,
            NetworkTuning tuning,
            Class<? extends Channel> backendChannel,
            CompressionRuntime compressionRuntime,
            MinecraftForwardingRuntime forwardingRuntime,
            boolean compressionRewriteEnabled,
            int compressionRewriteMaxEventLoopDelayMillis,
            MinecraftProtocolProfile profile) {
        this.metrics = metrics;
        this.tuning = tuning;
        this.backendChannel = backendChannel;
        this.compressionRuntime = compressionRuntime;
        this.forwardingRuntime = forwardingRuntime == null ? MinecraftForwardingRuntime.none() : forwardingRuntime;
        this.compressionRewriteEnabled = compressionRewriteEnabled;
        this.compressionRewriteMaxEventLoopDelayMillis = compressionRewriteMaxEventLoopDelayMillis;
        this.profile = profile == null
                ? MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_20_1)
                : profile;
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
                        var forgeHandshakeTracker = session.startForgeHandshakeTracker(tuning.maxFrameBytes(), profile);
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
                                profile,
                                replacementController,
                                forgeHandshakeTracker));
                    }
                });
        return bootstrap.connect(selected.descriptor().address());
    }

    ChannelFuture connectForSwitchLogin(
            Channel frontend,
            RegisteredServer selected,
            MinecraftCompressionAuditState compressionAudit,
            RelaySession session,
            MinecraftForgeHandshakeTracker forgeHandshakeTracker,
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
                                profile,
                                forgeHandshakeTracker,
                                listener));
                    }
                });
        return bootstrap.connect(selected.descriptor().address());
    }
}

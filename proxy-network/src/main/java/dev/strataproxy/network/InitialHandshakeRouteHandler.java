package dev.strataproxy.network;

import dev.strataproxy.api.server.RegisteredServer;
import dev.strataproxy.analysis.CustomPayloadAnomalyPolicy;
import dev.strataproxy.observability.ProxyMetrics;
import dev.strataproxy.observability.ProxyMetrics.CompressionDirection;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.net.SocketAddress;
import java.util.List;

final class InitialHandshakeRouteHandler extends ByteToMessageDecoder {
    private static final String RULE_MALFORMED_FRAME = "initial-handshake-malformed-frame";
    private static final String RULE_MALFORMED_HANDSHAKE = "initial-handshake-malformed-packet";
    private static final String RULE_NO_ROUTE = "initial-handshake-no-route";
    private static final String RULE_PENDING_TOO_LARGE = "initial-handshake-pending-too-large";

    private final BackendResolver backendResolver;
    private final ProxyMetrics metrics;
    private final NetworkTuning tuning;
    private final Class<? extends io.netty.channel.Channel> backendChannel;
    private final CompressionRuntime compressionRuntime;
    private final CustomPayloadAnomalyPolicy customPayloadPolicy;
    private final boolean compressionRewriteEnabled;
    private final int compressionRewriteMaxEventLoopDelayMillis;
    private boolean terminal;

    InitialHandshakeRouteHandler(BackendResolver backendResolver, ProxyMetrics metrics, NetworkTuning tuning) {
        this(backendResolver, metrics, tuning, io.netty.channel.socket.nio.NioSocketChannel.class, CompressionRuntime.defaults(), CustomPayloadAnomalyPolicy.defaults());
    }

    InitialHandshakeRouteHandler(
            BackendResolver backendResolver,
            ProxyMetrics metrics,
            NetworkTuning tuning,
            Class<? extends io.netty.channel.Channel> backendChannel,
            CompressionRuntime compressionRuntime,
            CustomPayloadAnomalyPolicy customPayloadPolicy) {
        this(backendResolver, metrics, tuning, backendChannel, compressionRuntime, customPayloadPolicy, false);
    }

    InitialHandshakeRouteHandler(
            BackendResolver backendResolver,
            ProxyMetrics metrics,
            NetworkTuning tuning,
            Class<? extends io.netty.channel.Channel> backendChannel,
            CompressionRuntime compressionRuntime,
            CustomPayloadAnomalyPolicy customPayloadPolicy,
            boolean compressionRewriteEnabled) {
        this(backendResolver, metrics, tuning, backendChannel, compressionRuntime, customPayloadPolicy, compressionRewriteEnabled, 25);
    }

    InitialHandshakeRouteHandler(
            BackendResolver backendResolver,
            ProxyMetrics metrics,
            NetworkTuning tuning,
            Class<? extends io.netty.channel.Channel> backendChannel,
            CompressionRuntime compressionRuntime,
            CustomPayloadAnomalyPolicy customPayloadPolicy,
            boolean compressionRewriteEnabled,
            int compressionRewriteMaxEventLoopDelayMillis) {
        this.backendResolver = backendResolver;
        this.metrics = metrics;
        this.tuning = tuning;
        this.backendChannel = backendChannel;
        this.compressionRuntime = compressionRuntime;
        this.customPayloadPolicy = customPayloadPolicy;
        this.compressionRewriteEnabled = compressionRewriteEnabled;
        this.compressionRewriteMaxEventLoopDelayMillis = compressionRewriteMaxEventLoopDelayMillis;
    }

    InitialHandshakeRouteHandler(
            BackendResolver backendResolver,
            ProxyMetrics metrics,
            NetworkTuning tuning,
            Class<? extends io.netty.channel.Channel> backendChannel,
            CompressionRuntime compressionRuntime) {
        this(backendResolver, metrics, tuning, backendChannel, compressionRuntime, CustomPayloadAnomalyPolicy.defaults());
    }

    @Override
    protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> output) {
        if (terminal) {
            return;
        }
        MinecraftProtocolCodec.FrameProbe probe;
        try {
            probe = MinecraftProtocolCodec.probeFrame(input, tuning.maxFrameBytes());
        } catch (RuntimeException exception) {
            metrics.packetAnomaly(
                    RULE_MALFORMED_FRAME,
                    remoteAddress(context.channel().remoteAddress()),
                    "",
                    "frontend_to_backend",
                    "HANDSHAKE",
                    -1,
                    input.readableBytes(),
                    -1,
                    exception.getMessage());
            metrics.failedRoute();
            terminal = true;
            context.close();
            return;
        }
        if (!probe.complete()) {
            return;
        }

        var firstFrame = input.readRetainedSlice(probe.totalBytes());
        var frontend = context.channel();
        frontend.config().setAutoRead(false);

        MinecraftHandshake handshake;
        RegisteredServer selected;
        try {
            handshake = MinecraftProtocolCodec.readHandshake(firstFrame, probe);
            selected = backendResolver.resolve(handshake, frontend.remoteAddress()).orElse(null);
        } catch (RuntimeException exception) {
            metrics.packetAnomaly(
                    RULE_MALFORMED_HANDSHAKE,
                    remoteAddress(frontend.remoteAddress()),
                    "",
                    "frontend_to_backend",
                    "HANDSHAKE",
                    0,
                    firstFrame.readableBytes(),
                    -1,
                    exception.getMessage());
            metrics.failedRoute();
            terminal = true;
            firstFrame.release();
            context.close();
            return;
        }

        if (selected == null) {
            metrics.packetAnomaly(
                    RULE_NO_ROUTE,
                    remoteAddress(frontend.remoteAddress()),
                    "",
                    "frontend_to_backend",
                    "HANDSHAKE",
                    0,
                    firstFrame.readableBytes(),
                    -1,
                    handshake.requestedHost());
            metrics.failedRoute();
            terminal = true;
            firstFrame.release();
            context.close();
            return;
        }

        if (input.readableBytes() > tuning.maxFrameBytes()) {
            metrics.packetAnomaly(
                    RULE_PENDING_TOO_LARGE,
                    remoteAddress(frontend.remoteAddress()),
                    selected.descriptor().name(),
                    "frontend_to_backend",
                    "HANDSHAKE",
                    0,
                    input.readableBytes(),
                    -1,
                    "pending bytes exceeded maximum before backend route");
            metrics.failedRoute();
            terminal = true;
            firstFrame.release();
            context.close();
            return;
        }

        var pendingBytes = input.isReadable() ? input.readRetainedSlice(input.readableBytes()) : null;
        connectBackend(context, firstFrame, pendingBytes, selected);
    }

    private void connectBackend(
            ChannelHandlerContext frontendContext,
            ByteBuf firstFrame,
            ByteBuf pendingBytes,
            RegisteredServer selected) {
        var frontend = frontendContext.channel();
        var serverName = selected.descriptor().name();
        var compressionAudit = new MinecraftCompressionAuditState(tuning.maxFrameBytes());
        var identity = new RelaySessionIdentity(remoteAddress(frontend.remoteAddress()));
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
                .handler(new ChannelInitializer<io.netty.channel.Channel>() {
                    @Override
                    protected void initChannel(io.netty.channel.Channel backend) {
                        backend.pipeline().addLast("backend-relay", new BackendRelayHandler(
                                frontend,
                                metrics,
                                serverName,
                                tuning.maxFrameBytes(),
                                compressionAudit,
                                compressionRuntime,
                                identity,
                                compressionRewriteEnabled,
                                compressionRewriteMaxEventLoopDelayMillis));
                    }
                });

        bootstrap.connect(selected.descriptor().address()).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                metrics.backendConnectFailure();
                release(firstFrame);
                release(pendingBytes);
                frontend.close();
                return;
            }

            var backend = future.channel();
            metrics.routedConnection();
            metrics.serverConnectionOpened(serverName);
            metrics.frontendToBackendBytes(serverName, firstFrame.readableBytes());
            if (pendingBytes != null) {
                metrics.frontendToBackendBytes(serverName, pendingBytes.readableBytes());
            }
            recordInitialPacketTraffic(serverName, firstFrame, pendingBytes);
            var initialPlayerName = pendingBytes == null ? null : observeInitialLoginStart(frontend, serverName, pendingBytes);
            if (initialPlayerName != null && !initialPlayerName.isBlank()) {
                identity.playerName(initialPlayerName);
            }
            frontend.pipeline().remove("initial-handshake-timeout");
            frontend.pipeline().replace(this, "frontend-relay", new FrontendRelayHandler(
                    backend,
                    metrics,
                    serverName,
                    compressionAudit,
                    compressionRuntime,
                    tuning.maxFrameBytes(),
                    customPayloadPolicy,
                    initialPlayerName,
                    identity,
                    compressionRewriteEnabled,
                    compressionRewriteMaxEventLoopDelayMillis));
            backend.write(firstFrame);
            if (pendingBytes != null) {
                backend.write(pendingBytes);
            }
            backend.flush();
            frontend.config().setAutoRead(false);
            backend.config().setAutoRead(false);
            frontend.read();
            backend.read();
        });
    }

    private void recordInitialPacketTraffic(String serverName, ByteBuf firstFrame, ByteBuf pendingBytes) {
        metrics.packetTraffic(
                serverName,
                CompressionDirection.FRONTEND_TO_BACKEND,
                "HANDSHAKE",
                0,
                firstFrame.readableBytes(),
                0);
        if (pendingBytes == null || !pendingBytes.isReadable()) {
            return;
        }
        var sampler = new MinecraftPacketTrafficSampler(tuning.maxFrameBytes());
        try {
            for (var sample : sampler.observe(pendingBytes)) {
                metrics.packetTraffic(
                        serverName,
                        CompressionDirection.FRONTEND_TO_BACKEND,
                        "UNCOMPRESSED",
                        sample.packetId(),
                        sample.frameBytes(),
                        0);
            }
        } catch (RuntimeException ignored) {
            // Traffic attribution must never block the initial fast-forward path.
        } finally {
            sampler.close();
        }
    }

    private String observeInitialLoginStart(io.netty.channel.Channel frontend, String serverName, ByteBuf pendingBytes) {
        var sampler = new MinecraftLoginStartSampler(tuning.maxFrameBytes());
        try {
            var username = sampler.observe(pendingBytes);
            if (username.isPresent()) {
                metrics.playerSessionStarted(username.get(), serverName, remoteAddress(frontend.remoteAddress()));
                return username.get();
            }
        } catch (RuntimeException ignored) {
            // Player attribution must never block the initial fast-forward path.
        } finally {
            sampler.close();
        }
        return null;
    }

    private static void release(ByteBuf buffer) {
        if (buffer != null) {
            buffer.release();
        }
    }

    private static String remoteAddress(SocketAddress address) {
        return address == null ? "" : address.toString();
    }
}

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
import io.netty.util.ReferenceCountUtil;

import java.net.SocketAddress;
import java.util.List;
import java.util.Optional;

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
    private final MinecraftAuthRuntime authRuntime;
    private final MinecraftForwardingRuntime forwardingRuntime;
    private final MinecraftStatusRuntime statusRuntime;
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
        this(backendResolver, metrics, tuning, backendChannel, compressionRuntime, customPayloadPolicy, MinecraftAuthRuntime.offline(), MinecraftForwardingRuntime.none(), MinecraftStatusRuntime.disabled(), false, 25);
    }

    InitialHandshakeRouteHandler(
            BackendResolver backendResolver,
            ProxyMetrics metrics,
            NetworkTuning tuning,
            Class<? extends io.netty.channel.Channel> backendChannel,
            CompressionRuntime compressionRuntime,
            CustomPayloadAnomalyPolicy customPayloadPolicy,
            boolean compressionRewriteEnabled) {
        this(backendResolver, metrics, tuning, backendChannel, compressionRuntime, customPayloadPolicy, MinecraftAuthRuntime.offline(), MinecraftForwardingRuntime.none(), MinecraftStatusRuntime.disabled(), compressionRewriteEnabled, 25);
    }

    InitialHandshakeRouteHandler(
            BackendResolver backendResolver,
            ProxyMetrics metrics,
            NetworkTuning tuning,
            Class<? extends io.netty.channel.Channel> backendChannel,
            CompressionRuntime compressionRuntime,
            CustomPayloadAnomalyPolicy customPayloadPolicy,
            MinecraftAuthRuntime authRuntime,
            MinecraftForwardingRuntime forwardingRuntime,
            MinecraftStatusRuntime statusRuntime,
            boolean compressionRewriteEnabled,
            int compressionRewriteMaxEventLoopDelayMillis) {
        this.backendResolver = backendResolver;
        this.metrics = metrics;
        this.tuning = tuning;
        this.backendChannel = backendChannel;
        this.compressionRuntime = compressionRuntime;
        this.customPayloadPolicy = customPayloadPolicy;
        this.authRuntime = authRuntime == null ? MinecraftAuthRuntime.offline() : authRuntime;
        this.forwardingRuntime = forwardingRuntime == null ? MinecraftForwardingRuntime.none() : forwardingRuntime;
        this.statusRuntime = statusRuntime == null ? MinecraftStatusRuntime.disabled() : statusRuntime;
        this.compressionRewriteEnabled = compressionRewriteEnabled;
        this.compressionRewriteMaxEventLoopDelayMillis = compressionRewriteMaxEventLoopDelayMillis;
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
        this(
                backendResolver,
                metrics,
                tuning,
                backendChannel,
                compressionRuntime,
                customPayloadPolicy,
                MinecraftAuthRuntime.offline(),
                MinecraftForwardingRuntime.none(),
                MinecraftStatusRuntime.disabled(),
                compressionRewriteEnabled,
                compressionRewriteMaxEventLoopDelayMillis);
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
        try {
            handshake = MinecraftProtocolCodec.readHandshake(firstFrame, probe);
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

        if (statusRuntime.enabled() && handshake.nextState() == 1) {
            var pendingBytes = input.isReadable() ? input.readRetainedSlice(input.readableBytes()) : null;
            beginLocalStatus(context, firstFrame, pendingBytes);
            return;
        }

        RegisteredServer selected;
        try {
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
        if (authRuntime.onlineMode() && handshake.nextState() == 2) {
            beginOnlineModeLogin(context, handshake, firstFrame, pendingBytes, selected);
        } else if (forwardingRuntime.bungeeHandshakeForwarding() && handshake.nextState() == 2) {
            beginBungeeLegacyLogin(context, handshake, firstFrame, pendingBytes, selected);
        } else {
            connectBackend(context, handshake, firstFrame, pendingBytes, selected);
        }
    }

    private void beginLocalStatus(ChannelHandlerContext context, ByteBuf firstFrame, ByteBuf pendingBytes) {
        release(firstFrame);
        var handler = new MinecraftStatusHandler(tuning.maxFrameBytes(), statusRuntime);
        context.pipeline().replace(this, "minecraft-status", handler);
        if (pendingBytes != null && pendingBytes.isReadable()) {
            var statusContext = context.pipeline().context("minecraft-status");
            try {
                handler.channelRead(statusContext, pendingBytes);
            } catch (Exception exception) {
                statusContext.fireExceptionCaught(exception);
                statusContext.close();
            }
        } else {
            release(pendingBytes);
            context.channel().read();
        }
    }

    private void beginOnlineModeLogin(
            ChannelHandlerContext context,
            MinecraftHandshake handshake,
            ByteBuf firstFrame,
            ByteBuf pendingBytes,
            RegisteredServer selected) {
        var consumed = new java.util.concurrent.atomic.AtomicBoolean();
        var identity = new RelaySessionIdentity(remoteAddress(context.channel().remoteAddress()));
        context.channel().closeFuture().addListener(ignored -> {
            if (consumed.compareAndSet(false, true)) {
                release(firstFrame);
                release(pendingBytes);
            }
        });
        var handler = new MinecraftOnlineModeLoginHandler(
                tuning.maxFrameBytes(),
                authRuntime.keyPair(),
                authRuntime.newVerifyToken(),
                authRuntime.sessionVerifier(),
                (authContext, loginStartFrame, sharedSecret, username, profile) -> {
                    if (consumed.compareAndSet(false, true)) {
                        release(pendingBytes);
                        identity.profile(profile == null
                                ? new MinecraftSessionVerifier.GameProfile(null, username, List.of())
                                : profile);
                        connectBackend(authContext, handshake, firstFrame, loginStartFrame, selected, "online-mode-login", identity);
                    } else {
                        release(loginStartFrame);
                    }
                });
        context.pipeline().replace(this, "online-mode-login", handler);
        if (pendingBytes != null && pendingBytes.isReadable()) {
            var authContext = context.pipeline().context("online-mode-login");
            try {
                handler.channelRead(authContext, pendingBytes.retainedDuplicate());
            } catch (Exception exception) {
                authContext.fireExceptionCaught(exception);
                authContext.close();
            }
        } else {
            context.channel().read();
        }
    }

    private void beginBungeeLegacyLogin(
            ChannelHandlerContext context,
            MinecraftHandshake handshake,
            ByteBuf firstFrame,
            ByteBuf pendingBytes,
            RegisteredServer selected) {
        var handler = new BungeeLegacyLoginStartHandler(handshake, firstFrame, selected);
        context.pipeline().replace(this, "bungee-legacy-login-start", handler);
        if (pendingBytes != null && pendingBytes.isReadable()) {
            var loginContext = context.pipeline().context("bungee-legacy-login-start");
            try {
                handler.channelRead(loginContext, pendingBytes);
            } catch (Exception exception) {
                loginContext.fireExceptionCaught(exception);
                loginContext.close();
            }
        } else {
            release(pendingBytes);
            context.channel().read();
        }
    }

    private void connectBackend(
            ChannelHandlerContext frontendContext,
            MinecraftHandshake handshake,
            ByteBuf firstFrame,
            ByteBuf pendingBytes,
            RegisteredServer selected) {
        connectBackend(frontendContext, handshake, firstFrame, pendingBytes, selected, null, null);
    }

    private void connectBackend(
            ChannelHandlerContext frontendContext,
            MinecraftHandshake handshake,
            ByteBuf firstFrame,
            ByteBuf pendingBytes,
            RegisteredServer selected,
            String frontendHandlerNameToReplace,
            RelaySessionIdentity existingIdentity) {
        var frontend = frontendContext.channel();
        var serverName = selected.descriptor().name();
        var compressionAudit = new MinecraftCompressionAuditState(tuning.maxFrameBytes());
        var identity = existingIdentity == null ? new RelaySessionIdentity(remoteAddress(frontend.remoteAddress())) : existingIdentity;
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
                                 forwardingRuntime,
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
            var initialLoginStart = pendingBytes == null ? null : observeInitialLoginStart(frontend, serverName, pendingBytes);
            if (initialLoginStart != null) {
                if (!initialLoginStart.username().isBlank()) {
                    identity.playerName(initialLoginStart.username());
                }
                initialLoginStart.chatSessionKey().ifPresent(identity::chatSessionKey);
            }
            var outboundFirstFrame = firstFrame;
            if (forwardingRuntime.bungeeHandshakeForwarding() && handshake.nextState() == 2) {
                try {
                    outboundFirstFrame = BungeeLegacyForwarding.rewriteHandshake(
                            frontendContext.alloc(),
                            handshake,
                            forwardingRuntime,
                            identity);
                    release(firstFrame);
                } catch (RuntimeException exception) {
                    metrics.backendConnectFailure();
                    release(firstFrame);
                    release(pendingBytes);
                    backend.close();
                    frontend.close();
                    return;
                }
            }
            metrics.frontendToBackendBytes(serverName, outboundFirstFrame.readableBytes());
            if (pendingBytes != null) {
                metrics.frontendToBackendBytes(serverName, pendingBytes.readableBytes());
            }
            recordInitialPacketTraffic(serverName, outboundFirstFrame, pendingBytes);
            frontend.pipeline().remove("initial-handshake-timeout");
            var frontendRelay = new FrontendRelayHandler(
                    backend,
                    metrics,
                    serverName,
                    compressionAudit,
                    compressionRuntime,
                    tuning.maxFrameBytes(),
                    customPayloadPolicy,
                    initialLoginStart == null ? null : initialLoginStart.username(),
                    identity,
                    compressionRewriteEnabled,
                    compressionRewriteMaxEventLoopDelayMillis);
            if (frontendHandlerNameToReplace == null) {
                frontend.pipeline().replace(this, "frontend-relay", frontendRelay);
            } else {
                frontend.pipeline().replace(frontendHandlerNameToReplace, "frontend-relay", frontendRelay);
            }
            backend.write(outboundFirstFrame);
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

    private MinecraftLoginStart observeInitialLoginStart(io.netty.channel.Channel frontend, String serverName, ByteBuf pendingBytes) {
        try {
            var loginStart = MinecraftLoginStart.read(pendingBytes, tuning.maxFrameBytes());
            metrics.playerSessionStarted(loginStart.username(), serverName, remoteAddress(frontend.remoteAddress()));
            return loginStart;
        } catch (RuntimeException ignored) {
            // Player attribution must never block the initial fast-forward path.
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

    private final class BungeeLegacyLoginStartHandler extends io.netty.channel.ChannelInboundHandlerAdapter {
        private final MinecraftHandshake handshake;
        private final ByteBuf firstFrame;
        private final RegisteredServer selected;
        private ByteBuf pending = io.netty.buffer.Unpooled.buffer();
        private boolean consumed;

        private BungeeLegacyLoginStartHandler(
                MinecraftHandshake handshake,
                ByteBuf firstFrame,
                RegisteredServer selected) {
            this.handshake = handshake;
            this.firstFrame = firstFrame;
            this.selected = selected;
        }

        @Override
        public void channelRead(ChannelHandlerContext context, Object message) {
            if (!(message instanceof ByteBuf buffer)) {
                ReferenceCountUtil.release(message);
                context.close();
                return;
            }
            try {
                if (pending.readableBytes() + buffer.readableBytes() > tuning.maxFrameBytes() + 5) {
                    throw new IllegalArgumentException("login start exceeded maximum frame size before bungee forwarding");
                }
                pending.writeBytes(buffer, buffer.readerIndex(), buffer.readableBytes());
                var probe = MinecraftProtocolCodec.probeFrame(pending, tuning.maxFrameBytes());
                if (!probe.complete()) {
                    context.channel().read();
                    return;
                }
                var loginStartFrame = pending.readRetainedSlice(probe.totalBytes());
                var remaining = pending.isReadable() ? pending.readRetainedSlice(pending.readableBytes()) : null;
                var outboundPending = combine(context, loginStartFrame, remaining);
                var loginStart = loginStart(outboundPending);
                var identity = new RelaySessionIdentity(remoteAddress(context.channel().remoteAddress()));
                loginStart.ifPresent(value -> {
                    identity.playerName(value.username());
                    value.chatSessionKey().ifPresent(identity::chatSessionKey);
                });
                consumed = true;
                releasePendingBuffer();
                connectBackend(context, handshake, firstFrame, outboundPending, selected, "bungee-legacy-login-start", identity);
            } catch (RuntimeException exception) {
                metrics.packetAnomaly(
                        RULE_MALFORMED_HANDSHAKE,
                        remoteAddress(context.channel().remoteAddress()),
                        selected.descriptor().name(),
                        "frontend_to_backend",
                        "LOGIN",
                        0,
                        pending.readableBytes(),
                        -1,
                        exception.getMessage());
                metrics.failedRoute();
                context.close();
            } finally {
                buffer.release();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) {
            releaseOwnedBuffers();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            context.close();
            releaseOwnedBuffers();
        }

        private ByteBuf combine(ChannelHandlerContext context, ByteBuf loginStartFrame, ByteBuf remaining) {
            try {
                if (remaining == null || !remaining.isReadable()) {
                    return loginStartFrame.retain();
                }
                var combined = context.alloc().buffer(loginStartFrame.readableBytes() + remaining.readableBytes());
                combined.writeBytes(loginStartFrame, loginStartFrame.readerIndex(), loginStartFrame.readableBytes());
                combined.writeBytes(remaining, remaining.readerIndex(), remaining.readableBytes());
                return combined;
            } finally {
                release(loginStartFrame);
                release(remaining);
            }
        }

        private Optional<MinecraftLoginStart> loginStart(ByteBuf loginStartFrame) {
            try {
                return Optional.of(MinecraftLoginStart.read(loginStartFrame, tuning.maxFrameBytes()));
            } catch (RuntimeException exception) {
                return Optional.empty();
            }
        }

        private void releaseOwnedBuffers() {
            if (consumed) {
                return;
            }
            consumed = true;
            release(firstFrame);
            releasePendingBuffer();
        }

        private void releasePendingBuffer() {
            if (pending.refCnt() > 0) {
                pending.release();
            }
            pending = io.netty.buffer.Unpooled.EMPTY_BUFFER;
        }
    }
}

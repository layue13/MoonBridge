package dev.strataproxy.infrastructure.minecraft;

import dev.strataproxy.domain.server.RegisteredServer;
import dev.strataproxy.infrastructure.observability.ProxyMetrics;
import dev.strataproxy.infrastructure.observability.ProxyMetrics.CompressionDirection;
import dev.strataproxy.plugin.command.CommandRegistry;
import dev.strataproxy.plugin.event.EventBus;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.util.ReferenceCountUtil;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

final class InitialHandshakeRouteHandler extends ByteToMessageDecoder {
    private static final String RULE_MALFORMED_FRAME = "initial-handshake-malformed-frame";
    private static final String RULE_MALFORMED_HANDSHAKE = "initial-handshake-malformed-packet";
    private static final String RULE_NO_ROUTE = "initial-handshake-no-route";
    private static final String RULE_PENDING_TOO_LARGE = "initial-handshake-pending-too-large";
    private static final String DISCONNECT_NO_ROUTE = "No available backend server for this route.";
    private static final String DISCONNECT_PENDING_TOO_LARGE = "Login request is too large.";
    private static final String DISCONNECT_BACKEND_UNAVAILABLE = "Backend server is unavailable.";

    private final BackendResolver backendResolver;
    private final ProxyMetrics metrics;
    private final NetworkTuning tuning;
    private final Class<? extends io.netty.channel.Channel> backendChannel;
    private final CompressionRuntime compressionRuntime;
    private final MinecraftAuthRuntime authRuntime;
    private final MinecraftForwardingRuntime forwardingRuntime;
    private final MinecraftStatusRuntime statusRuntime;
    private final RelaySessionRegistry relaySessions;
    private final CommandRegistry commands;
    private final EventBus events;
    private final boolean compressionRewriteEnabled;
    private final int compressionRewriteMaxEventLoopDelayMillis;
    private boolean terminal;

    InitialHandshakeRouteHandler(BackendResolver backendResolver, ProxyMetrics metrics, NetworkTuning tuning) {
        this(backendResolver, metrics, tuning, io.netty.channel.socket.nio.NioSocketChannel.class, CompressionRuntime.defaults(), MinecraftAuthRuntime.offline(), MinecraftForwardingRuntime.none(), MinecraftStatusRuntime.disabled(), false, 25);
    }

    InitialHandshakeRouteHandler(
            BackendResolver backendResolver,
            ProxyMetrics metrics,
            NetworkTuning tuning,
            Class<? extends io.netty.channel.Channel> backendChannel,
            CompressionRuntime compressionRuntime,
            MinecraftAuthRuntime authRuntime,
            MinecraftForwardingRuntime forwardingRuntime,
            MinecraftStatusRuntime statusRuntime,
            boolean compressionRewriteEnabled,
            int compressionRewriteMaxEventLoopDelayMillis) {
        this(
                backendResolver,
                metrics,
                tuning,
                backendChannel,
                compressionRuntime,
                authRuntime,
                forwardingRuntime,
                statusRuntime,
                compressionRewriteEnabled,
                compressionRewriteMaxEventLoopDelayMillis,
                new RelaySessionRegistry());
    }

    InitialHandshakeRouteHandler(
            BackendResolver backendResolver,
            ProxyMetrics metrics,
            NetworkTuning tuning,
            Class<? extends io.netty.channel.Channel> backendChannel,
            CompressionRuntime compressionRuntime,
            MinecraftAuthRuntime authRuntime,
            MinecraftForwardingRuntime forwardingRuntime,
            MinecraftStatusRuntime statusRuntime,
            boolean compressionRewriteEnabled,
            int compressionRewriteMaxEventLoopDelayMillis,
            RelaySessionRegistry relaySessions) {
        this(
                backendResolver,
                metrics,
                tuning,
                backendChannel,
                compressionRuntime,
                authRuntime,
                forwardingRuntime,
                statusRuntime,
                compressionRewriteEnabled,
                compressionRewriteMaxEventLoopDelayMillis,
                relaySessions,
                null,
                null);
    }

    InitialHandshakeRouteHandler(
            BackendResolver backendResolver,
            ProxyMetrics metrics,
            NetworkTuning tuning,
            Class<? extends io.netty.channel.Channel> backendChannel,
            CompressionRuntime compressionRuntime,
            MinecraftAuthRuntime authRuntime,
            MinecraftForwardingRuntime forwardingRuntime,
            MinecraftStatusRuntime statusRuntime,
            boolean compressionRewriteEnabled,
            int compressionRewriteMaxEventLoopDelayMillis,
            RelaySessionRegistry relaySessions,
            CommandRegistry commands,
            EventBus events) {
        this.backendResolver = backendResolver;
        this.metrics = metrics;
        this.tuning = tuning;
        this.backendChannel = backendChannel;
        this.compressionRuntime = compressionRuntime;
        this.authRuntime = authRuntime == null ? MinecraftAuthRuntime.offline() : authRuntime;
        this.forwardingRuntime = forwardingRuntime == null ? MinecraftForwardingRuntime.none() : forwardingRuntime;
        this.statusRuntime = statusRuntime == null ? MinecraftStatusRuntime.disabled() : statusRuntime;
        this.relaySessions = relaySessions;
        this.commands = commands;
        this.events = events;
        this.compressionRewriteEnabled = compressionRewriteEnabled;
        this.compressionRewriteMaxEventLoopDelayMillis = compressionRewriteMaxEventLoopDelayMillis;
    }

    InitialHandshakeRouteHandler(
            BackendResolver backendResolver,
            ProxyMetrics metrics,
            NetworkTuning tuning,
            Class<? extends io.netty.channel.Channel> backendChannel,
            CompressionRuntime compressionRuntime) {
        this(backendResolver, metrics, tuning, backendChannel, compressionRuntime, MinecraftAuthRuntime.offline(), MinecraftForwardingRuntime.none(), MinecraftStatusRuntime.disabled(), false, 25);
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

        MinecraftHandshake handshake = null;
        try {
            handshake = MinecraftProtocolCodec.readHandshake(firstFrame, probe);
        } catch (RuntimeException exception) {
            metrics.failedRoute();
            terminal = true;
            firstFrame.release();
            scheduleCloseLogin(context, handshake, DISCONNECT_PENDING_TOO_LARGE);
            return;
        }

        if (statusRuntime.enabled() && handshake.nextState() == 1) {
            var pendingBytes = input.isReadable() ? input.readRetainedSlice(input.readableBytes()) : null;
            beginLocalStatus(context, firstFrame, pendingBytes);
            return;
        }

        RegisteredServer selected;
        try {
            selected = backendResolver.resolve(handshake, ClientAddress.socketAddress(frontend)).orElse(null);
        } catch (RuntimeException exception) {
            metrics.failedRoute();
            terminal = true;
            firstFrame.release();
            scheduleCloseLogin(context, handshake, DISCONNECT_NO_ROUTE);
            return;
        }

        if (selected == null) {
            var pendingBytes = input.isReadable() ? input.readRetainedSlice(input.readableBytes()) : null;
            metrics.failedRoute();
            terminal = true;
            firstFrame.release();
            beginFailingLogin(context, handshake, pendingBytes, DISCONNECT_NO_ROUTE);
            return;
        }

        if (input.readableBytes() > tuning.maxFrameBytes()) {
            metrics.failedRoute();
            terminal = true;
            firstFrame.release();
            scheduleCloseLogin(context, handshake, DISCONNECT_PENDING_TOO_LARGE);
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

    private void beginFailingLogin(
            ChannelHandlerContext context,
            MinecraftHandshake handshake,
            ByteBuf pendingBytes,
            String reason) {
        if (handshake.nextState() != 2) {
            release(pendingBytes);
            context.close();
            return;
        }
        var handler = new LoginFailureHandler(handshake, reason);
        context.pipeline().replace(this, "login-failure", handler);
        if (pendingBytes != null && pendingBytes.isReadable()) {
            var failureContext = context.pipeline().context("login-failure");
            try {
                handler.channelRead(failureContext, pendingBytes);
            } catch (Exception exception) {
                failureContext.fireExceptionCaught(exception);
                failureContext.close();
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
        var identity = new RelaySessionIdentity(ClientAddress.text(context.channel()));
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
        var session = new RelaySession(existingIdentity == null ? new RelaySessionIdentity(ClientAddress.text(frontend)) : existingIdentity);
        var identity = session.identity();
        var profile = MinecraftProtocolProfile.forVersion(handshake.protocolVersion());
        session.legacyForgeClientDetected(profile.legacyForgeHandshakeSupported() && handshake.legacyForgeClientMarker());
        var backendConnector = new BackendConnector(
                metrics,
                tuning,
                backendChannel,
                compressionRuntime,
                forwardingRuntime,
                compressionRewriteEnabled,
                compressionRewriteMaxEventLoopDelayMillis,
                profile);
        var replacementController = new BackendReplacementController(
                ServerTargetResolver.from(backendResolver),
                backendConnector,
                metrics,
                tuning,
                compressionRuntime,
                session,
                relaySessions,
                compressionRewriteEnabled,
                compressionRewriteMaxEventLoopDelayMillis,
                commands,
                events,
                profile);

        backendConnector.connect(frontend, selected, compressionAudit, session, replacementController).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                metrics.backendConnectFailure();
                release(firstFrame);
                release(pendingBytes);
                closeLogin(frontendContext, handshake, DISCONNECT_BACKEND_UNAVAILABLE);
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
                    closeLogin(frontendContext, handshake, DISCONNECT_BACKEND_UNAVAILABLE);
                    return;
                }
            }
            metrics.frontendToBackendBytes(serverName, outboundFirstFrame.readableBytes());
            if (pendingBytes != null) {
                metrics.frontendToBackendBytes(serverName, pendingBytes.readableBytes());
            }
            if (handshake.nextState() == 2) {
                session.loginSession(new RelayLoginSession(outboundFirstFrame, pendingBytes));
            }
            frontend.pipeline().remove("initial-handshake-timeout");
            var loginStartSeed = initialLoginStart == null && pendingBytes != null && pendingBytes.isReadable()
                    ? pendingBytes.retainedDuplicate()
                    : null;
            try {
                var frontendRelay = new FrontendRelayHandler(
                        backend,
                        metrics,
                        serverName,
                        compressionAudit,
                        compressionRuntime,
                        tuning.maxFrameBytes(),
                        initialLoginStart == null ? null : initialLoginStart.username(),
                        identity,
                        compressionRewriteEnabled,
                        compressionRewriteMaxEventLoopDelayMillis,
                        replacementController,
                        commands,
                        events,
                        profile,
                        loginStartSeed,
                        session.forgeHandshakeTracker());
                replacementController.relayAttached(frontendRelay, frontend, backend, serverName);
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
                if (initialLoginStart != null) {
                    metrics.playerSessionStarted(initialLoginStart.username(), identity.playerId(), identity.connectionId(), serverName, ClientAddress.text(frontend));
                }
                frontend.read();
                backend.read();
            } finally {
                if (loginStartSeed != null && loginStartSeed.refCnt() > 0) {
                    loginStartSeed.release();
                }
            }
        });
    }

    private MinecraftLoginStart observeInitialLoginStart(io.netty.channel.Channel frontend, String serverName, ByteBuf pendingBytes) {
        try {
            return MinecraftLoginStart.read(pendingBytes, tuning.maxFrameBytes());
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

    private void closeLogin(ChannelHandlerContext context, MinecraftHandshake handshake, String reason) {
        if (handshake != null && handshake.nextState() == 2 && context.channel().isActive()) {
            context.channel().writeAndFlush(MinecraftLoginDisconnect.frame(context.alloc(), reason))
                    .addListener((ChannelFutureListener) ignored ->
                            context.executor().schedule(() -> context.close(), 25, TimeUnit.MILLISECONDS));
        } else {
            context.close();
        }
    }

    private void scheduleCloseLogin(ChannelHandlerContext context, MinecraftHandshake handshake, String reason) {
        context.executor().execute(() -> closeLogin(context, handshake, reason));
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
        /** Provides channel read. */
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
                var identity = new RelaySessionIdentity(ClientAddress.text(context.channel()));
                loginStart.ifPresent(value -> {
                    identity.playerName(value.username());
                    value.chatSessionKey().ifPresent(identity::chatSessionKey);
                });
                consumed = true;
                releasePendingBuffer();
                connectBackend(context, handshake, firstFrame, outboundPending, selected, "bungee-legacy-login-start", identity);
            } catch (RuntimeException exception) {
                metrics.failedRoute();
                context.close();
            } finally {
                buffer.release();
            }
        }

        @Override
        /** Provides channel inactive. */
        public void channelInactive(ChannelHandlerContext context) {
            releaseOwnedBuffers();
        }

        @Override
        /** Provides exception caught. */
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

    private final class LoginFailureHandler extends io.netty.channel.ChannelInboundHandlerAdapter {
        private final MinecraftHandshake handshake;
        private final String reason;
        private ByteBuf pending = io.netty.buffer.Unpooled.buffer();
        private boolean closed;

        private LoginFailureHandler(MinecraftHandshake handshake, String reason) {
            this.handshake = handshake;
            this.reason = reason;
        }

        @Override
        /** Provides channel read. */
        public void channelRead(ChannelHandlerContext context, Object message) {
            if (!(message instanceof ByteBuf buffer)) {
                ReferenceCountUtil.release(message);
                context.close();
                return;
            }
            try {
                if (pending.readableBytes() + buffer.readableBytes() > tuning.maxFrameBytes() + 5) {
                    closeNow(context);
                    return;
                }
                pending.writeBytes(buffer, buffer.readerIndex(), buffer.readableBytes());
                var probe = MinecraftProtocolCodec.probeFrame(pending, tuning.maxFrameBytes());
                if (!probe.complete()) {
                    context.channel().read();
                    return;
                }
                closeNow(context);
            } finally {
                buffer.release();
            }
        }

        @Override
        /** Provides channel inactive. */
        public void channelInactive(ChannelHandlerContext context) {
            releasePending();
        }

        @Override
        /** Provides exception caught. */
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            context.close();
            releasePending();
        }

        private void closeNow(ChannelHandlerContext context) {
            if (closed) {
                return;
            }
            closed = true;
            releasePending();
            closeLogin(context, handshake, reason);
        }

        private void releasePending() {
            if (pending.refCnt() > 0) {
                pending.release();
            }
            pending = io.netty.buffer.Unpooled.EMPTY_BUFFER;
        }
    }
}

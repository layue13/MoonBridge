package dev.strataproxy.network;

import dev.strataproxy.codec.minecraft.MinecraftCompressionCodec;
import dev.strataproxy.compression.CompressionAction;
import dev.strataproxy.observability.ProxyMetrics;
import dev.strataproxy.observability.ProxyMetrics.CompressionDirection;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;

import java.util.ArrayList;
import java.util.List;

final class BackendRelayHandler extends ChannelInboundHandlerAdapter {
    static final AttributeKey<BackendRelayHandler> HANDLER = AttributeKey.valueOf("strataproxy.backendRelayHandler");

    private static final String RULE_COMPRESSION_NEGOTIATION_MALFORMED = "compression-negotiation-malformed";
    private static final String RULE_COMPRESSION_AUDIT_MALFORMED = "compression-audit-malformed";
    private static final String RULE_LEGACY_SWITCH_JOIN_GAME_MALFORMED = "legacy-switch-join-game-malformed";
    private static final String RULE_LEGACY_FORGE_HANDSHAKE_GATE_MALFORMED = "legacy-forge-handshake-gate-malformed";
    private static final int MAX_LEGACY_FORGE_QUEUED_BYTES = 1_048_576;

    private final Channel frontend;
    private final ProxyMetrics metrics;
    private final String serverName;
    private final MinecraftCompressionAuditState compressionAudit;
    private final MinecraftCompressionNegotiationDetector compressionDetector;
    private final MinecraftCompressionCodec legacySwitchCompressionCodec = new MinecraftCompressionCodec();
    private final CompressionRuntime compressionRuntime;
    private final MinecraftPacketTrafficSampler packetTrafficSampler;
    private final MinecraftLoginPluginRequestInspectionSampler loginPluginRequestInspection;
    private final BungeeConnectRequestSampler bungeeConnectSampler;
    private final RelaySessionIdentity identity;
    private final MinecraftForwardingRuntime forwardingRuntime;
    private final CompressionRewriteRuntime compressionRewriteRuntime;
    private final BackendReplacementController replacementController;
    private final int maxFrameBytes;
    private final MinecraftProtocolProfile profile;
    private final MinecraftForgeHandshakeTracker forgeHandshakeTracker;
    private ByteBuf legacyJoinGamePending;
    private ByteBuf legacyForgeGatePending;
    private ByteBuf legacyForgeQueuedFrames;
    private ChannelHandlerContext backendContext;
    private MinecraftProtocolProfile.BackendSwitchStrategy backendSwitchStrategy;
    private boolean replayPluginChannelsAfterClientboundFlush;
    private boolean deferredBackendReplacementReady;
    private boolean closed;

    BackendRelayHandler(Channel frontend, ProxyMetrics metrics, String serverName) {
        this(frontend, metrics, serverName, NetworkTuning.defaults().maxFrameBytes());
    }

    BackendRelayHandler(Channel frontend, ProxyMetrics metrics, String serverName, int maxFrameBytes) {
        this(frontend, metrics, serverName, maxFrameBytes, new MinecraftCompressionAuditState(maxFrameBytes));
    }

    BackendRelayHandler(
            Channel frontend,
            ProxyMetrics metrics,
            String serverName,
            int maxFrameBytes,
            MinecraftCompressionAuditState compressionAudit) {
        this(frontend, metrics, serverName, maxFrameBytes, compressionAudit, CompressionRuntime.defaults());
    }

    BackendRelayHandler(
            Channel frontend,
            ProxyMetrics metrics,
            String serverName,
            int maxFrameBytes,
            MinecraftCompressionAuditState compressionAudit,
            CompressionRuntime compressionRuntime) {
        this(frontend, metrics, serverName, maxFrameBytes, compressionAudit, compressionRuntime, new RelaySessionIdentity(""));
    }

    BackendRelayHandler(
            Channel frontend,
            ProxyMetrics metrics,
            String serverName,
            int maxFrameBytes,
            MinecraftCompressionAuditState compressionAudit,
            CompressionRuntime compressionRuntime,
            RelaySessionIdentity identity) {
        this(frontend, metrics, serverName, maxFrameBytes, compressionAudit, compressionRuntime, identity, false);
    }

    BackendRelayHandler(
            Channel frontend,
            ProxyMetrics metrics,
            String serverName,
            int maxFrameBytes,
            MinecraftCompressionAuditState compressionAudit,
            CompressionRuntime compressionRuntime,
            RelaySessionIdentity identity,
            boolean compressionRewriteEnabled) {
        this(frontend, metrics, serverName, maxFrameBytes, compressionAudit, compressionRuntime, identity, compressionRewriteEnabled, 25);
    }

    BackendRelayHandler(
            Channel frontend,
            ProxyMetrics metrics,
            String serverName,
            int maxFrameBytes,
            MinecraftCompressionAuditState compressionAudit,
            CompressionRuntime compressionRuntime,
            RelaySessionIdentity identity,
            boolean compressionRewriteEnabled,
            int compressionRewriteMaxEventLoopDelayMillis) {
        this(frontend, metrics, serverName, maxFrameBytes, compressionAudit, compressionRuntime, identity, MinecraftForwardingRuntime.none(), compressionRewriteEnabled, compressionRewriteMaxEventLoopDelayMillis);
    }

    BackendRelayHandler(
            Channel frontend,
            ProxyMetrics metrics,
            String serverName,
            int maxFrameBytes,
            MinecraftCompressionAuditState compressionAudit,
            CompressionRuntime compressionRuntime,
            RelaySessionIdentity identity,
            MinecraftForwardingRuntime forwardingRuntime,
            boolean compressionRewriteEnabled,
            int compressionRewriteMaxEventLoopDelayMillis) {
        this(
                frontend,
                metrics,
                serverName,
                maxFrameBytes,
                compressionAudit,
                compressionRuntime,
                identity,
                forwardingRuntime,
                compressionRewriteEnabled,
                compressionRewriteMaxEventLoopDelayMillis,
                null);
    }

    BackendRelayHandler(
            Channel frontend,
            ProxyMetrics metrics,
            String serverName,
            int maxFrameBytes,
            MinecraftCompressionAuditState compressionAudit,
            CompressionRuntime compressionRuntime,
            RelaySessionIdentity identity,
            MinecraftForwardingRuntime forwardingRuntime,
            boolean compressionRewriteEnabled,
            int compressionRewriteMaxEventLoopDelayMillis,
            BackendReplacementController replacementController) {
        this(
                frontend,
                metrics,
                serverName,
                maxFrameBytes,
                compressionAudit,
                compressionRuntime,
                identity,
                forwardingRuntime,
                compressionRewriteEnabled,
                compressionRewriteMaxEventLoopDelayMillis,
                763,
                replacementController);
    }

    BackendRelayHandler(
            Channel frontend,
            ProxyMetrics metrics,
            String serverName,
            int maxFrameBytes,
            MinecraftCompressionAuditState compressionAudit,
            CompressionRuntime compressionRuntime,
            RelaySessionIdentity identity,
            MinecraftForwardingRuntime forwardingRuntime,
            boolean compressionRewriteEnabled,
            int compressionRewriteMaxEventLoopDelayMillis,
            int protocolVersion,
            BackendReplacementController replacementController) {
        this(
                frontend,
                metrics,
                serverName,
                maxFrameBytes,
                compressionAudit,
                compressionRuntime,
                identity,
                forwardingRuntime,
                compressionRewriteEnabled,
                compressionRewriteMaxEventLoopDelayMillis,
                MinecraftProtocolProfile.forVersion(protocolVersion),
                replacementController);
    }

    BackendRelayHandler(
            Channel frontend,
            ProxyMetrics metrics,
            String serverName,
            int maxFrameBytes,
            MinecraftCompressionAuditState compressionAudit,
            CompressionRuntime compressionRuntime,
            RelaySessionIdentity identity,
            MinecraftForwardingRuntime forwardingRuntime,
            boolean compressionRewriteEnabled,
            int compressionRewriteMaxEventLoopDelayMillis,
            MinecraftProtocolProfile profile,
            BackendReplacementController replacementController) {
        this(
                frontend,
                metrics,
                serverName,
                maxFrameBytes,
                compressionAudit,
                compressionRuntime,
                identity,
                forwardingRuntime,
                compressionRewriteEnabled,
                compressionRewriteMaxEventLoopDelayMillis,
                profile,
                replacementController,
                null);
    }

    BackendRelayHandler(
            Channel frontend,
            ProxyMetrics metrics,
            String serverName,
            int maxFrameBytes,
            MinecraftCompressionAuditState compressionAudit,
            CompressionRuntime compressionRuntime,
            RelaySessionIdentity identity,
            MinecraftForwardingRuntime forwardingRuntime,
            boolean compressionRewriteEnabled,
            int compressionRewriteMaxEventLoopDelayMillis,
            MinecraftProtocolProfile profile,
            BackendReplacementController replacementController,
            MinecraftForgeHandshakeTracker forgeHandshakeTracker) {
        this(
                frontend,
                metrics,
                serverName,
                maxFrameBytes,
                compressionAudit,
                compressionRuntime,
                identity,
                forwardingRuntime,
                compressionRewriteEnabled,
                compressionRewriteMaxEventLoopDelayMillis,
                profile,
                replacementController,
                forgeHandshakeTracker,
                false);
    }

    BackendRelayHandler(
            Channel frontend,
            ProxyMetrics metrics,
            String serverName,
            int maxFrameBytes,
            MinecraftCompressionAuditState compressionAudit,
            CompressionRuntime compressionRuntime,
            RelaySessionIdentity identity,
            MinecraftForwardingRuntime forwardingRuntime,
            boolean compressionRewriteEnabled,
            int compressionRewriteMaxEventLoopDelayMillis,
            MinecraftProtocolProfile profile,
            BackendReplacementController replacementController,
            MinecraftForgeHandshakeTracker forgeHandshakeTracker,
            boolean convertNextLegacyJoinGameToRespawn) {
        this(
                frontend,
                metrics,
                serverName,
                maxFrameBytes,
                compressionAudit,
                compressionRuntime,
                identity,
                forwardingRuntime,
                compressionRewriteEnabled,
                compressionRewriteMaxEventLoopDelayMillis,
                profile,
                replacementController,
                forgeHandshakeTracker,
                convertNextLegacyJoinGameToRespawn
                        ? MinecraftProtocolProfile.BackendSwitchStrategy.RESPAWN_ONLY
                        : MinecraftProtocolProfile.BackendSwitchStrategy.NONE);
    }

    BackendRelayHandler(
            Channel frontend,
            ProxyMetrics metrics,
            String serverName,
            int maxFrameBytes,
            MinecraftCompressionAuditState compressionAudit,
            CompressionRuntime compressionRuntime,
            RelaySessionIdentity identity,
            MinecraftForwardingRuntime forwardingRuntime,
            boolean compressionRewriteEnabled,
            int compressionRewriteMaxEventLoopDelayMillis,
            MinecraftProtocolProfile profile,
            BackendReplacementController replacementController,
            MinecraftForgeHandshakeTracker forgeHandshakeTracker,
            boolean convertNextLegacyJoinGameToRespawn,
            boolean includeLegacySwitchJoinGame) {
        this(
                frontend,
                metrics,
                serverName,
                maxFrameBytes,
                compressionAudit,
                compressionRuntime,
                identity,
                forwardingRuntime,
                compressionRewriteEnabled,
                compressionRewriteMaxEventLoopDelayMillis,
                profile,
                replacementController,
                forgeHandshakeTracker,
                convertNextLegacyJoinGameToRespawn
                        ? includeLegacySwitchJoinGame
                                ? MinecraftProtocolProfile.BackendSwitchStrategy.JOIN_GAME_THEN_RESPAWN
                                : MinecraftProtocolProfile.BackendSwitchStrategy.RESPAWN_ONLY
                        : MinecraftProtocolProfile.BackendSwitchStrategy.NONE);
    }

    BackendRelayHandler(
            Channel frontend,
            ProxyMetrics metrics,
            String serverName,
            int maxFrameBytes,
            MinecraftCompressionAuditState compressionAudit,
            CompressionRuntime compressionRuntime,
            RelaySessionIdentity identity,
            MinecraftForwardingRuntime forwardingRuntime,
            boolean compressionRewriteEnabled,
            int compressionRewriteMaxEventLoopDelayMillis,
            MinecraftProtocolProfile profile,
            BackendReplacementController replacementController,
            MinecraftForgeHandshakeTracker forgeHandshakeTracker,
            MinecraftProtocolProfile.BackendSwitchStrategy backendSwitchStrategy) {
        this.frontend = frontend;
        this.metrics = metrics;
        this.serverName = serverName;
        this.compressionAudit = compressionAudit;
        this.profile = profile == null
                ? MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_20_1)
                : profile;
        this.compressionDetector = new MinecraftCompressionNegotiationDetector(maxFrameBytes, this.profile);
        this.compressionRuntime = compressionRuntime;
        this.packetTrafficSampler = new MinecraftPacketTrafficSampler(maxFrameBytes);
        this.loginPluginRequestInspection = new MinecraftLoginPluginRequestInspectionSampler(maxFrameBytes, maxFrameBytes);
        this.bungeeConnectSampler = new BungeeConnectRequestSampler(maxFrameBytes, this.profile);
        this.identity = identity;
        this.forwardingRuntime = forwardingRuntime == null ? MinecraftForwardingRuntime.none() : forwardingRuntime;
        this.compressionRewriteRuntime = new CompressionRewriteRuntime(compressionRewriteEnabled, compressionRewriteMaxEventLoopDelayMillis);
        this.replacementController = replacementController;
        this.maxFrameBytes = maxFrameBytes;
        this.forgeHandshakeTracker = forgeHandshakeTracker;
        this.backendSwitchStrategy = backendSwitchStrategy == null
                ? MinecraftProtocolProfile.BackendSwitchStrategy.NONE
                : backendSwitchStrategy;
    }

    @Override
    /** Provides handler added. */
    public void handlerAdded(ChannelHandlerContext context) {
        backendContext = context;
        context.channel().attr(HANDLER).set(this);
    }

    @Override
    /** Provides handler removed. */
    public void handlerRemoved(ChannelHandlerContext context) {
        context.channel().attr(HANDLER).set(null);
        backendContext = null;
    }

    @Override
    /** Provides channel read. */
    public void channelRead(ChannelHandlerContext context, Object message) {
        if (!frontend.isActive()) {
            ReferenceCountUtil.release(message);
            context.close();
            return;
        }
        var outbound = message;
        var forward = true;
        List<CompressionAction> compressionActions = List.of();
        if (message instanceof ByteBuf buffer) {
            ByteBuf converted;
            try {
                converted = convertLegacySwitchJoinGameIfNeeded(context, buffer);
            } catch (RuntimeException exception) {
                ReferenceCountUtil.release(outbound);
                context.close();
                if (frontend.isOpen()) {
                    frontend.close();
                }
                return;
            }
            if (converted == null) {
                ReferenceCountUtil.release(message);
                context.channel().read();
                return;
            }
            if (converted != buffer) {
                outbound = converted;
                ReferenceCountUtil.release(message);
                buffer = converted;
            }
            var legacyForgeBlockedBefore = legacyForgeHandshakeBlocksClientboundFrames();
            var forwardingRequest = observeVelocityForwardingRequest(context, buffer);
            if (forwardingRequest.matched()) {
                ReferenceCountUtil.release(outbound);
                var response = VelocityModernForwarding.response(
                        context.alloc(),
                        forwardingRequest.messageId(),
                        forwardingRequest.version(),
                        forwardingRuntime,
                        identity);
                context.writeAndFlush(response).addListener((ChannelFutureListener) future -> {
                    if (future.isSuccess()) {
                        context.channel().read();
                    } else {
                        context.close();
                        if (frontend.isOpen()) {
                            frontend.close();
                        }
                    }
                });
                return;
            }
            if (observeBungeeConnectRequest(context, buffer)) {
                ReferenceCountUtil.release(outbound);
                return;
            }
            metrics.backendToFrontendBytes(serverName, buffer.readableBytes());
            capturePayloadPrefix(buffer, CompressionDirection.BACKEND_TO_FRONTEND);
            observeForgeHandshake(context, buffer);
            observeLegacyClientState(context, buffer);
            observeLoginPluginRequests(buffer);
            observePacketTraffic(buffer);
            var compression = observeCompression(buffer);
            forward = compression.shouldForward();
            compressionActions = compression.actions();
            if (forward && outbound instanceof ByteBuf outboundBuffer) {
                ByteBuf gated;
                try {
                    gated = gateLegacyForgeHandshakeFrames(context, outboundBuffer, legacyForgeBlockedBefore);
                } catch (RuntimeException exception) {
                    ReferenceCountUtil.release(outbound);
                    context.close();
                    if (frontend.isOpen()) {
                        frontend.close();
                    }
                    return;
                }
                if (gated == null) {
                    ReferenceCountUtil.release(outbound);
                    context.channel().read();
                    return;
                }
                if (gated != outboundBuffer) {
                    ReferenceCountUtil.release(outbound);
                    outbound = gated;
                    buffer = gated;
                }
            }
            if (forward && compression.rewriteEligible()) {
                var rewrite = compressionRewriteRuntime.rewrite(
                        context.alloc(),
                        metrics,
                        serverName,
                        CompressionDirection.BACKEND_TO_FRONTEND,
                        buffer,
                        compressionAudit.threshold(),
                        compressionActions,
                        maxFrameBytes);
                if (rewrite.suppressed()) {
                    ReferenceCountUtil.release(outbound);
                    context.channel().read();
                    return;
                }
                if (rewrite.replaced()) {
                    ReferenceCountUtil.release(outbound);
                    outbound = rewrite.frame();
                }
            }
        }
        if (!forward) {
            ReferenceCountUtil.release(outbound);
            context.close();
            if (frontend.isOpen()) {
                frontend.close();
            }
            return;
        }
        recordBackpressureIfNeeded(frontend);
        frontend.writeAndFlush(outbound).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                replayPluginChannelsAfterClientboundFlush(context.channel());
                flushQueuedLegacyForgeFramesIfReady();
                runDeferredBackendReplacementIfReady();
                context.channel().read();
            } else {
                future.channel().close();
                context.close();
            }
        });
    }

    private ByteBuf convertLegacySwitchJoinGameIfNeeded(ChannelHandlerContext context, ByteBuf buffer) {
        if (backendSwitchStrategy == MinecraftProtocolProfile.BackendSwitchStrategy.NONE
                || profile.clientboundPlayLoginPacketId().isEmpty()) {
            return buffer;
        }
        if (legacyJoinGamePending == null) {
            legacyJoinGamePending = context.alloc().buffer(buffer.readableBytes());
        }
        if (legacyJoinGamePending.readableBytes() + buffer.readableBytes() > maxFrameBytes + 5) {
            recordLegacyJoinGameConversionFailure(
                    "pending_overflow",
                    legacyJoinGamePending.readableBytes() + buffer.readableBytes());
            releaseLegacyJoinGamePending();
            backendSwitchStrategy = MinecraftProtocolProfile.BackendSwitchStrategy.NONE;
            throw new IllegalStateException("legacy switch Join Game frame exceeded maximum size");
        }
        legacyJoinGamePending.writeBytes(buffer, buffer.readerIndex(), buffer.readableBytes());
        var output = context.alloc().buffer(legacyJoinGamePending.readableBytes());
        try {
            while (legacyJoinGamePending.isReadable()) {
                var probe = MinecraftProtocolCodec.probeFrame(legacyJoinGamePending, maxFrameBytes);
                if (!probe.complete()) {
                    break;
                }
                var frame = legacyJoinGamePending.readRetainedSlice(probe.totalBytes());
                try {
                    convertLegacySwitchJoinGameFrame(context, output, frame);
                } finally {
                    frame.release();
                }
                if (backendSwitchStrategy == MinecraftProtocolProfile.BackendSwitchStrategy.NONE) {
                    if (legacyJoinGamePending.isReadable()) {
                        output.writeBytes(legacyJoinGamePending, legacyJoinGamePending.readerIndex(), legacyJoinGamePending.readableBytes());
                    }
                    releaseLegacyJoinGamePending();
                    return output;
                }
                legacyJoinGamePending.discardReadBytes();
            }
            if (output.isReadable()) {
                return output;
            }
            output.release();
            return null;
        } catch (RuntimeException exception) {
            output.release();
            recordLegacyJoinGameConversionFailure("malformed", legacyJoinGamePending.readableBytes());
            releaseLegacyJoinGamePending();
            backendSwitchStrategy = MinecraftProtocolProfile.BackendSwitchStrategy.NONE;
            throw exception;
        }
    }

    private void convertLegacySwitchJoinGameFrame(ChannelHandlerContext context, ByteBuf output, ByteBuf frame) {
        var packet = decodeLegacySwitchFrame(context, frame);
        try {
            var packetStart = packet.readerIndex();
            var packetId = MinecraftProtocolCodec.readVarInt(packet);
            if (packetId == profile.clientboundPlayLoginPacketId().getAsInt()) {
                packet.readerIndex(packetStart);
                if (backendSwitchStrategy == MinecraftProtocolProfile.BackendSwitchStrategy.JOIN_GAME_THEN_RESPAWN) {
                    output.writeBytes(frame, frame.readerIndex(), frame.readableBytes());
                }
                var respawns = compressionAudit.negotiated()
                        ? MinecraftLegacyTransferPackets.compressedRespawnSequenceFromJoinGame(
                                context.alloc(),
                                packet,
                                profile,
                                legacySwitchCompressionCodec,
                                compressionAudit.threshold())
                        : MinecraftLegacyTransferPackets.respawnSequenceFromJoinGame(context.alloc(), packet, profile);
                try {
                    output.writeBytes(respawns, respawns.readerIndex(), respawns.readableBytes());
                } finally {
                    respawns.release();
                }
                replayPluginChannelsAfterClientboundFlush = true;
                backendSwitchStrategy = MinecraftProtocolProfile.BackendSwitchStrategy.NONE;
            } else {
                output.writeBytes(frame, frame.readerIndex(), frame.readableBytes());
            }
        } finally {
            packet.release();
        }
    }

    private ByteBuf decodeLegacySwitchFrame(ChannelHandlerContext context, ByteBuf frame) {
        if (compressionAudit.negotiated()) {
            var duplicate = frame.retainedDuplicate();
            try {
                return legacySwitchCompressionCodec.decodeFrame(
                        context.alloc(),
                        duplicate,
                        compressionAudit.threshold(),
                        maxFrameBytes);
            } finally {
                duplicate.release();
            }
        }
        var packet = frame.retainedDuplicate();
        try {
            MinecraftProtocolCodec.readVarInt(packet);
            return packet.readRetainedSlice(packet.readableBytes());
        } finally {
            packet.release();
        }
    }

    private void replayPluginChannelsAfterClientboundFlush(Channel backend) {
        if (!replayPluginChannelsAfterClientboundFlush || replacementController == null) {
            return;
        }
        replayPluginChannelsAfterClientboundFlush = false;
        replacementController.replayServerboundPluginChannels(backend, compressionAudit);
    }

    private void recordLegacyJoinGameConversionFailure(String reason, int bytes) {
        metrics.packetAnomaly(
                RULE_LEGACY_SWITCH_JOIN_GAME_MALFORMED,
                identity.remoteAddress(),
                serverName,
                CompressionDirection.BACKEND_TO_FRONTEND.label(),
                "PLAY",
                profile.clientboundPlayLoginPacketId().orElse(-1),
                Math.max(0, bytes),
                -1,
                reason);
    }

    private void releaseLegacyJoinGamePending() {
        if (legacyJoinGamePending != null && legacyJoinGamePending.refCnt() > 0) {
            legacyJoinGamePending.release();
        }
        legacyJoinGamePending = null;
    }

    private VelocityModernForwarding.ForwardingRequest observeVelocityForwardingRequest(ChannelHandlerContext context, ByteBuf buffer) {
        if (!forwardingRuntime.velocityModern() || compressionAudit.negotiated()) {
            return VelocityModernForwarding.ForwardingRequest.none();
        }
        return VelocityModernForwarding.request(buffer, maxFrameBytes);
    }

    @Override
    /** Provides channel inactive. */
    public void channelInactive(ChannelHandlerContext context) {
        closeBackendSide();
        if (!Boolean.TRUE.equals(context.channel().attr(BackendReplacementController.MIGRATING_BACKEND).get()) && frontend.isOpen()) {
            frontend.close();
        }
    }

    @Override
    /** Provides exception caught. */
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        context.close();
        closeBackendSide();
        if (frontend.isOpen()) {
            frontend.close();
        }
    }

    private void closeBackendSide() {
        if (closed) {
            return;
        }
        closed = true;
        compressionDetector.close();
        legacySwitchCompressionCodec.close();
        compressionAudit.closeBackendSampler();
        packetTrafficSampler.close();
        loginPluginRequestInspection.close();
        bungeeConnectSampler.close();
        compressionRewriteRuntime.close();
        releaseLegacyJoinGamePending();
        releaseLegacyForgeGatePending();
        releaseLegacyForgeQueuedFrames();
        metrics.forgeHandshakeClosed(serverName, identity.playerName(), identity.remoteAddress());
    }

    private void observeForgeHandshake(ChannelHandlerContext context, ByteBuf buffer) {
        if (forgeHandshakeTracker == null) {
            return;
        }
        try {
            var events = compressionAudit.negotiated()
                    ? forgeHandshakeTracker.observeCompressed(
                            MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                            context.alloc(),
                            buffer,
                            compressionAudit.threshold())
                    : forgeHandshakeTracker.observe(MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND, buffer);
            for (var event : events) {
                if ("register".equals(event.type())
                        && forgeHandshakeTracker.consumeResetRequiredOnNextForgeServer()
                        && frontend.isActive()) {
                    forgeHandshakeTracker.resetHandshakeFromProxy();
                    frontend.write(forgeHandshakeResetFrame(context));
                }
                recordForgeHandshakeEvent(event);
            }
            recordForgeHandshakeState();
            markDeferredBackendReplacementReady();
        } catch (RuntimeException exception) {
            metrics.packetAnomaly(
                    "forge-handshake-inspection-malformed",
                    identity.remoteAddress(),
                    serverName,
                    CompressionDirection.BACKEND_TO_FRONTEND.label(),
                    "PLAY",
                    -1,
                    buffer.readableBytes(),
                    -1,
                    exception.getMessage());
        }
    }

    private ByteBuf forgeHandshakeResetFrame(ChannelHandlerContext context) {
        var reset = MinecraftLegacyTransferPackets.forgeHandshakeResetFrame(context.alloc(), profile);
        if (!compressionAudit.negotiated()) {
            return reset;
        }
        try {
            return MinecraftLegacyTransferPackets.compressFrameBatch(
                    context.alloc(),
                    reset,
                    legacySwitchCompressionCodec,
                    compressionAudit.threshold(),
                    maxFrameBytes);
        } finally {
            reset.release();
        }
    }

    private void observeLegacyClientState(ChannelHandlerContext context, ByteBuf buffer) {
        if (replacementController == null) {
            return;
        }
        try {
            if (compressionAudit.negotiated()) {
                replacementController.observeCompressedLegacyClientState(
                        context.alloc(),
                        buffer,
                        compressionAudit.threshold(),
                        profile);
            } else {
                replacementController.observeLegacyClientState(buffer, profile);
            }
        } catch (RuntimeException exception) {
            metrics.packetAnomaly(
                    "legacy-client-state-inspection-malformed",
                    identity.remoteAddress(),
                    serverName,
                    CompressionDirection.BACKEND_TO_FRONTEND.label(),
                    "PLAY",
                    -1,
                    buffer.readableBytes(),
                    -1,
                    exception.getMessage());
        }
    }

    private void markDeferredBackendReplacementReady() {
        if (replacementController != null && forgeHandshakeTracker.complete()) {
            deferredBackendReplacementReady = true;
        }
    }

    private void runDeferredBackendReplacementIfReady() {
        if (deferredBackendReplacementReady && replacementController != null && forgeHandshakeTracker.complete()) {
            deferredBackendReplacementReady = false;
            replacementController.runDeferredBackendReplacementIfReady();
        }
    }

    private boolean legacyForgeHandshakeBlocksClientboundFrames() {
        return forgeHandshakeTracker != null
                && profile.legacyForgeHandshakeSupported()
                && forgeHandshakeTracker.backendSwitchBlocked();
    }

    private ByteBuf gateLegacyForgeHandshakeFrames(
            ChannelHandlerContext context,
            ByteBuf buffer,
            boolean legacyForgeBlockedBefore) {
        if (!legacyForgeBlockedBefore || !profile.legacyForgeHandshakeSupported()) {
            return buffer;
        }
        if (legacyForgeGatePending == null) {
            legacyForgeGatePending = context.alloc().buffer(buffer.readableBytes());
        }
        if (legacyForgeGatePending.readableBytes() + buffer.readableBytes() > maxFrameBytes + 5) {
            recordLegacyForgeGateFailure("pending_overflow", legacyForgeGatePending.readableBytes() + buffer.readableBytes());
            releaseLegacyForgeGatePending();
            throw new IllegalStateException("legacy Forge handshake gate pending frame exceeded maximum size");
        }
        legacyForgeGatePending.writeBytes(buffer, buffer.readerIndex(), buffer.readableBytes());
        var output = context.alloc().buffer(legacyForgeGatePending.readableBytes());
        try {
            while (legacyForgeGatePending.isReadable()) {
                var probe = MinecraftProtocolCodec.probeFrame(legacyForgeGatePending, maxFrameBytes);
                if (!probe.complete()) {
                    break;
                }
                var frame = legacyForgeGatePending.readRetainedSlice(probe.totalBytes());
                try {
                    if (legacyForgeFrameAllowedDuringHandshake(context, frame)) {
                        output.writeBytes(frame, frame.readerIndex(), frame.readableBytes());
                    } else {
                        queueLegacyForgeFrame(context, frame);
                    }
                } finally {
                    frame.release();
                }
                legacyForgeGatePending.discardReadBytes();
            }
            if (output.isReadable()) {
                return output;
            }
            output.release();
            return null;
        } catch (RuntimeException exception) {
            output.release();
            releaseLegacyForgeGatePending();
            throw exception;
        }
    }

    private boolean legacyForgeFrameAllowedDuringHandshake(ChannelHandlerContext context, ByteBuf frame) {
        var packet = legacyForgeGatePacket(context, frame);
        try {
            var packetId = MinecraftProtocolCodec.readVarInt(packet);
            if (profile.clientboundCustomPayloadPacketId().isEmpty()
                    || packetId != profile.clientboundCustomPayloadPacketId().getAsInt()) {
                return true;
            }
            var channel = MinecraftProtocolCodec.readString(packet, 128);
            return legacyForgeChannel(channel);
        } finally {
            packet.release();
        }
    }

    private ByteBuf legacyForgeGatePacket(ChannelHandlerContext context, ByteBuf frame) {
        if (compressionAudit.negotiated()) {
            var duplicate = frame.retainedDuplicate();
            try {
                return legacySwitchCompressionCodec.decodeFrame(
                        context.alloc(),
                        duplicate,
                        compressionAudit.threshold(),
                        maxFrameBytes);
            } finally {
                duplicate.release();
            }
        }
        var packet = frame.retainedDuplicate();
        try {
            MinecraftProtocolCodec.readVarInt(packet);
            return packet.readRetainedSlice(packet.readableBytes());
        } finally {
            packet.release();
        }
    }

    private void queueLegacyForgeFrame(ChannelHandlerContext context, ByteBuf frame) {
        if (legacyForgeQueuedFrames == null) {
            legacyForgeQueuedFrames = context.alloc().buffer(Math.min(frame.readableBytes(), 1024));
        }
        if (legacyForgeQueuedFrames.readableBytes() + frame.readableBytes() > MAX_LEGACY_FORGE_QUEUED_BYTES) {
            recordLegacyForgeGateFailure("queue_overflow", legacyForgeQueuedFrames.readableBytes() + frame.readableBytes());
            releaseLegacyForgeQueuedFrames();
            throw new IllegalStateException("legacy Forge handshake queued frames exceeded maximum size");
        }
        legacyForgeQueuedFrames.writeBytes(frame, frame.readerIndex(), frame.readableBytes());
    }

    void flushQueuedLegacyForgeFramesIfReady() {
        if (legacyForgeQueuedFrames == null
                || !legacyForgeQueuedFrames.isReadable()
                || forgeHandshakeTracker == null
                || !forgeHandshakeTracker.complete()
                || !frontend.isActive()) {
            return;
        }
        var queued = legacyForgeQueuedFrames;
        legacyForgeQueuedFrames = null;
        frontend.writeAndFlush(queued).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                future.channel().close();
                if (backendContext != null) {
                    backendContext.close();
                }
            }
        });
    }

    private void recordLegacyForgeGateFailure(String reason, int bytes) {
        metrics.packetAnomaly(
                RULE_LEGACY_FORGE_HANDSHAKE_GATE_MALFORMED,
                identity.remoteAddress(),
                serverName,
                CompressionDirection.BACKEND_TO_FRONTEND.label(),
                "PLAY",
                -1,
                Math.max(0, bytes),
                -1,
                reason);
    }

    private static boolean legacyForgeChannel(String channel) {
        return "REGISTER".equals(channel)
                || "FML|HS".equals(channel)
                || "FML|MP".equals(channel)
                || "FML".equals(channel)
                || "FORGE".equals(channel);
    }

    private void releaseLegacyForgeGatePending() {
        if (legacyForgeGatePending != null && legacyForgeGatePending.refCnt() > 0) {
            legacyForgeGatePending.release();
        }
        legacyForgeGatePending = null;
    }

    private void releaseLegacyForgeQueuedFrames() {
        if (legacyForgeQueuedFrames != null && legacyForgeQueuedFrames.refCnt() > 0) {
            legacyForgeQueuedFrames.release();
        }
        legacyForgeQueuedFrames = null;
    }

    private void recordForgeHandshakeEvent(MinecraftForgeHandshakeTracker.Event event) {
        metrics.customPayload(
                serverName,
                CompressionDirection.BACKEND_TO_FRONTEND,
                "FORGE_HANDSHAKE_" + event.type().toUpperCase(java.util.Locale.ROOT),
                event.channel(),
                event.payloadBytes(),
                0,
                identity.playerName(),
                identity.remoteAddress(),
                "PLAY",
                profile.clientboundCustomPayloadPacketId().orElse(-1));
        if (!event.expected()) {
            metrics.packetAnomaly(
                    "forge-handshake-unexpected-order",
                    identity.remoteAddress(),
                    serverName,
                    CompressionDirection.BACKEND_TO_FRONTEND.label(),
                    "PLAY",
                    profile.clientboundCustomPayloadPacketId().orElse(-1),
                    event.payloadBytes(),
                    -1,
                    event.type() + " stage=" + event.stage());
        }
    }

    private void recordForgeHandshakeState() {
        if (forgeHandshakeTracker == null) {
            return;
        }
        metrics.forgeHandshake(
                serverName,
                identity.playerName(),
                identity.remoteAddress(),
                forgeHandshakeTracker.stage().name(),
                forgeHandshakeTracker.clientPhase().name(),
                forgeHandshakeTracker.backendPhase().name(),
                forgeHandshakeTracker.complete(),
                forgeHandshakeTracker.backendSwitchBlocked(),
                forgeHandshakeTracker.clientModCount(),
                forgeHandshakeTracker.serverModCount(),
                forgeHandshakeTracker.clientboundRegistryPackets(),
                forgeHandshakeTracker.clientboundRegistryBytes());
    }

    private boolean observeBungeeConnectRequest(ChannelHandlerContext context, ByteBuf buffer) {
        if (replacementController == null) {
            return false;
        }
        try {
            var requests = compressionAudit.negotiated()
                    ? bungeeConnectSampler.observeCompressed(context.alloc(), buffer, compressionAudit.threshold())
                    : bungeeConnectSampler.observeUncompressed(buffer);
            for (var request : requests) {
                replacementController.replaceBackend(request.targetServer());
                return true;
            }
        } catch (RuntimeException ignored) {
            bungeeConnectSampler.close();
        }
        return false;
    }

    private void observeLoginPluginRequests(ByteBuf buffer) {
        if (compressionAudit.negotiated()) {
            loginPluginRequestInspection.close();
            return;
        }
        try {
            for (var classification : loginPluginRequestInspection.observe(buffer)) {
                metrics.customPayload(
                        serverName,
                        CompressionDirection.BACKEND_TO_FRONTEND,
                        classification.kind().name(),
                        classification.channel(),
                        classification.payloadBytes(),
                        0,
                        identity.playerName(),
                        identity.remoteAddress(),
                        "LOGIN",
                        classification.packetId());
            }
        } catch (RuntimeException exception) {
            metrics.packetAnomaly(
                    "login-plugin-request-inspection-malformed",
                    identity.remoteAddress(),
                    serverName,
                    CompressionDirection.BACKEND_TO_FRONTEND.label(),
                    "LOGIN",
                    -1,
                    buffer.readableBytes(),
                    -1,
                    exception.getMessage());
            loginPluginRequestInspection.close();
        }
    }

    private void observePacketTraffic(ByteBuf buffer) {
        if (compressionAudit.negotiated()) {
            packetTrafficSampler.close();
            return;
        }
        try {
            for (var sample : packetTrafficSampler.observe(buffer)) {
                metrics.packetTraffic(
                        serverName,
                        CompressionDirection.BACKEND_TO_FRONTEND,
                        "UNCOMPRESSED",
                        sample.packetId(),
                        sample.frameBytes(),
                        0);
            }
        } catch (RuntimeException exception) {
            packetTrafficSampler.close();
        }
    }

    private CompressionObservation observeCompression(ByteBuf buffer) {
        if (compressionAudit.negotiated()) {
            return observeCompressionFrames(buffer);
        }
        try {
            var threshold = compressionDetector.observe(buffer);
            if (threshold.isPresent()) {
                metrics.compressionNegotiated(serverName, threshold.getAsInt());
                compressionAudit.negotiate(threshold.getAsInt());
            }
            return CompressionObservation.allow();
        } catch (RuntimeException exception) {
            metrics.packetAnomaly(
                    RULE_COMPRESSION_NEGOTIATION_MALFORMED,
                    "",
                    serverName,
                    CompressionDirection.BACKEND_TO_FRONTEND.label(),
                    "LOGIN",
                    -1,
                    buffer.readableBytes(),
                    -1,
                    exception.getMessage());
            return CompressionObservation.block();
        }
    }

    private CompressionObservation observeCompressionFrames(ByteBuf buffer) {
        try {
            var actions = new ArrayList<CompressionAction>();
            for (var sample : compressionAudit.observeBackend(buffer)) {
                metrics.compressionSample(
                        serverName,
                        CompressionDirection.BACKEND_TO_FRONTEND,
                        sample.rawBytes(),
                        sample.compressedBytes(),
                        0);
                actions.add(compressionRuntime.recordDecision(
                        metrics,
                        serverName,
                        CompressionDirection.BACKEND_TO_FRONTEND,
                        sample.rawBytes(),
                        ratio(sample),
                        0));
            }
            return new CompressionObservation(true, true, List.copyOf(actions));
        } catch (RuntimeException exception) {
            metrics.packetAnomaly(
                    RULE_COMPRESSION_AUDIT_MALFORMED,
                    "",
                    serverName,
                    CompressionDirection.BACKEND_TO_FRONTEND.label(),
                    "PLAY",
                    -1,
                    buffer.readableBytes(),
                    -1,
                    exception.getMessage());
            compressionAudit.closeBackendSampler();
            return CompressionObservation.block();
        }
    }

    private static double ratio(MinecraftCompressedFrameAuditSampler.CompressionFrameSample sample) {
        return sample.rawBytes() == 0 ? 1.0d : (double) sample.compressedBytes() / sample.rawBytes();
    }

    private void recordBackpressureIfNeeded(Channel target) {
        if (!target.isWritable()) {
            metrics.relayBackpressure(serverName, CompressionDirection.BACKEND_TO_FRONTEND, target.bytesBeforeWritable());
        }
    }

    private void capturePayloadPrefix(ByteBuf buffer, CompressionDirection direction) {
        var request = metrics.payloadCaptureRequest(serverName, direction);
        if (!request.enabled()) {
            return;
        }
        var bytes = new byte[Math.min(buffer.readableBytes(), request.maxBytesPerSample())];
        buffer.getBytes(buffer.readerIndex(), bytes);
        metrics.payloadCaptured(
                serverName,
                direction,
                buffer.readableBytes(),
                compressionAudit.negotiated() ? buffer.readableBytes() : -1,
                bytes,
                identity.playerName(),
                identity.remoteAddress());
    }

    private record CompressionObservation(boolean shouldForward, boolean rewriteEligible, List<CompressionAction> actions) {
        private static CompressionObservation allow() {
            return new CompressionObservation(true, false, List.of());
        }

        private static CompressionObservation block() {
            return new CompressionObservation(false, false, List.of());
        }
    }
}

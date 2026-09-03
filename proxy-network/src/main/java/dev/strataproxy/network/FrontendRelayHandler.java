package dev.strataproxy.network;

import dev.strataproxy.analysis.CustomPayloadAnomalyPolicy;
import dev.strataproxy.analysis.AnomalyAction;
import dev.strataproxy.compression.CompressionAction;
import dev.strataproxy.codec.minecraft.MinecraftCompressionCodec;
import dev.strataproxy.observability.ProxyMetrics;
import dev.strataproxy.observability.ProxyMetrics.CompressionDirection;
import dev.strataproxy.analysis.PacketAnomaly;
import dev.strataproxy.plugin.command.CommandRegistry;
import dev.strataproxy.plugin.command.CommandSource;
import dev.strataproxy.plugin.event.EventBus;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

import java.util.ArrayList;
import java.util.List;

final class FrontendRelayHandler extends ChannelInboundHandlerAdapter {
    private static final String RULE_COMPRESSION_AUDIT_MALFORMED = "compression-audit-malformed";
    private static final String RULE_LEGACY_FORGE_HANDSHAKE_GATE_MALFORMED = "legacy-forge-handshake-gate-malformed";
    private static final int MAX_LEGACY_FORGE_QUEUED_BYTES = 1_048_576;

    private final Channel backend;
    private final ProxyMetrics metrics;
    private final String serverName;
    private final MinecraftCompressionAuditState compressionAudit;
    private final CompressionRuntime compressionRuntime;
    private final MinecraftCustomPayloadInspectionSampler customPayloadInspection;
    private final MinecraftPacketTrafficSampler packetTrafficSampler;
    private final MinecraftLoginStartSampler loginStartSampler;
    private final MinecraftCompressionCodec legacyForgeGateCompressionCodec = new MinecraftCompressionCodec();
    private final int maxFrameBytes;
    private final CustomPayloadAnomalyPolicy customPayloadPolicy;
    private final RelaySessionIdentity identity;
    private final CompressionRewriteRuntime compressionRewriteRuntime;
    private final BackendReplacementController replacementController;
    private final CommandRegistry commands;
    private final EventBus events;
    private final int protocolVersion;
    private final MinecraftProtocolProfile profile;
    private final MinecraftForgeHandshakeTracker forgeHandshakeTracker;
    private MinecraftCompressedCustomPayloadInspectionSampler compressedCustomPayloadInspection;
    private GameCommandFrameInterceptor commandInterceptor;
    private ByteBuf legacyForgeRacePending;
    private ByteBuf legacyForgeGatePending;
    private ByteBuf legacyForgeQueuedFrames;
    private String playerName;
    private boolean deferredBackendReplacementReady;
    private boolean closed;

    FrontendRelayHandler(Channel backend, ProxyMetrics metrics, String serverName) {
        this(backend, metrics, serverName, new MinecraftCompressionAuditState(NetworkTuning.defaults().maxFrameBytes()));
    }

    FrontendRelayHandler(Channel backend, ProxyMetrics metrics, String serverName, MinecraftCompressionAuditState compressionAudit) {
        this(backend, metrics, serverName, compressionAudit, CompressionRuntime.defaults());
    }

    FrontendRelayHandler(
            Channel backend,
            ProxyMetrics metrics,
            String serverName,
            MinecraftCompressionAuditState compressionAudit,
            CompressionRuntime compressionRuntime) {
        this(backend, metrics, serverName, compressionAudit, compressionRuntime, NetworkTuning.defaults().maxFrameBytes());
    }

    FrontendRelayHandler(
            Channel backend,
            ProxyMetrics metrics,
            String serverName,
            MinecraftCompressionAuditState compressionAudit,
            CompressionRuntime compressionRuntime,
            int maxFrameBytes) {
        this(backend, metrics, serverName, compressionAudit, compressionRuntime, maxFrameBytes, CustomPayloadAnomalyPolicy.defaults());
    }

    FrontendRelayHandler(
            Channel backend,
            ProxyMetrics metrics,
            String serverName,
            MinecraftCompressionAuditState compressionAudit,
            CompressionRuntime compressionRuntime,
            int maxFrameBytes,
            CustomPayloadAnomalyPolicy customPayloadPolicy) {
        this(backend, metrics, serverName, compressionAudit, compressionRuntime, maxFrameBytes, customPayloadPolicy, null);
    }

    FrontendRelayHandler(
            Channel backend,
            ProxyMetrics metrics,
            String serverName,
            MinecraftCompressionAuditState compressionAudit,
            CompressionRuntime compressionRuntime,
            int maxFrameBytes,
            CustomPayloadAnomalyPolicy customPayloadPolicy,
            String initialPlayerName) {
        this(backend, metrics, serverName, compressionAudit, compressionRuntime, maxFrameBytes, customPayloadPolicy, initialPlayerName, new RelaySessionIdentity(""));
    }

    FrontendRelayHandler(
            Channel backend,
            ProxyMetrics metrics,
            String serverName,
            MinecraftCompressionAuditState compressionAudit,
            CompressionRuntime compressionRuntime,
            int maxFrameBytes,
            CustomPayloadAnomalyPolicy customPayloadPolicy,
            String initialPlayerName,
            RelaySessionIdentity identity) {
        this(backend, metrics, serverName, compressionAudit, compressionRuntime, maxFrameBytes, customPayloadPolicy, initialPlayerName, identity, false);
    }

    FrontendRelayHandler(
            Channel backend,
            ProxyMetrics metrics,
            String serverName,
            MinecraftCompressionAuditState compressionAudit,
            CompressionRuntime compressionRuntime,
            int maxFrameBytes,
            CustomPayloadAnomalyPolicy customPayloadPolicy,
            String initialPlayerName,
            RelaySessionIdentity identity,
            boolean compressionRewriteEnabled) {
        this(backend, metrics, serverName, compressionAudit, compressionRuntime, maxFrameBytes, customPayloadPolicy, initialPlayerName, identity, compressionRewriteEnabled, 25);
    }

    FrontendRelayHandler(
            Channel backend,
            ProxyMetrics metrics,
            String serverName,
            MinecraftCompressionAuditState compressionAudit,
            CompressionRuntime compressionRuntime,
            int maxFrameBytes,
            CustomPayloadAnomalyPolicy customPayloadPolicy,
            String initialPlayerName,
            RelaySessionIdentity identity,
            boolean compressionRewriteEnabled,
            int compressionRewriteMaxEventLoopDelayMillis) {
        this(
                backend,
                metrics,
                serverName,
                compressionAudit,
                compressionRuntime,
                maxFrameBytes,
                customPayloadPolicy,
                initialPlayerName,
                identity,
                compressionRewriteEnabled,
                compressionRewriteMaxEventLoopDelayMillis,
                null,
                null,
                null,
                763,
                null);
    }

    FrontendRelayHandler(
            Channel backend,
            ProxyMetrics metrics,
            String serverName,
            MinecraftCompressionAuditState compressionAudit,
            CompressionRuntime compressionRuntime,
            int maxFrameBytes,
            CustomPayloadAnomalyPolicy customPayloadPolicy,
            String initialPlayerName,
            RelaySessionIdentity identity,
            boolean compressionRewriteEnabled,
            int compressionRewriteMaxEventLoopDelayMillis,
            BackendReplacementController replacementController,
            CommandRegistry commands,
            EventBus events,
            int protocolVersion,
            ByteBuf initialLoginStartSeed) {
        this(
                backend,
                metrics,
                serverName,
                compressionAudit,
                compressionRuntime,
                maxFrameBytes,
                customPayloadPolicy,
                initialPlayerName,
                identity,
                compressionRewriteEnabled,
                compressionRewriteMaxEventLoopDelayMillis,
                replacementController,
                commands,
                events,
                MinecraftProtocolProfile.forVersion(protocolVersion),
                initialLoginStartSeed,
                null);
    }

    FrontendRelayHandler(
            Channel backend,
            ProxyMetrics metrics,
            String serverName,
            MinecraftCompressionAuditState compressionAudit,
            CompressionRuntime compressionRuntime,
            int maxFrameBytes,
            CustomPayloadAnomalyPolicy customPayloadPolicy,
            String initialPlayerName,
            RelaySessionIdentity identity,
            boolean compressionRewriteEnabled,
            int compressionRewriteMaxEventLoopDelayMillis,
            BackendReplacementController replacementController,
            CommandRegistry commands,
            EventBus events,
            MinecraftProtocolProfile profile,
            ByteBuf initialLoginStartSeed) {
        this(
                backend,
                metrics,
                serverName,
                compressionAudit,
                compressionRuntime,
                maxFrameBytes,
                customPayloadPolicy,
                initialPlayerName,
                identity,
                compressionRewriteEnabled,
                compressionRewriteMaxEventLoopDelayMillis,
                replacementController,
                commands,
                events,
                profile,
                initialLoginStartSeed,
                null);
    }

    FrontendRelayHandler(
            Channel backend,
            ProxyMetrics metrics,
            String serverName,
            MinecraftCompressionAuditState compressionAudit,
            CompressionRuntime compressionRuntime,
            int maxFrameBytes,
            CustomPayloadAnomalyPolicy customPayloadPolicy,
            String initialPlayerName,
            RelaySessionIdentity identity,
            boolean compressionRewriteEnabled,
            int compressionRewriteMaxEventLoopDelayMillis,
            BackendReplacementController replacementController,
            CommandRegistry commands,
            EventBus events,
            MinecraftProtocolProfile profile,
            ByteBuf initialLoginStartSeed,
            MinecraftForgeHandshakeTracker forgeHandshakeTracker) {
        this.backend = backend;
        this.metrics = metrics;
        this.serverName = serverName;
        this.compressionAudit = compressionAudit;
        this.compressionRuntime = compressionRuntime;
        this.maxFrameBytes = maxFrameBytes;
        this.customPayloadPolicy = customPayloadPolicy;
        this.profile = profile == null
                ? MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_20_1)
                : profile;
        this.customPayloadInspection = new MinecraftCustomPayloadInspectionSampler(
                maxFrameBytes,
                Math.min(maxFrameBytes, customPayloadPolicy.largePayloadWarnBytes()),
                customPayloadPolicy,
                this.profile);
        this.packetTrafficSampler = new MinecraftPacketTrafficSampler(maxFrameBytes);
        this.loginStartSampler = initialLoginStartSeed == null
                ? new MinecraftLoginStartSampler(maxFrameBytes)
                : new MinecraftLoginStartSampler(maxFrameBytes, initialLoginStartSeed);
        this.identity = identity;
        this.compressionRewriteRuntime = new CompressionRewriteRuntime(compressionRewriteEnabled, compressionRewriteMaxEventLoopDelayMillis);
        this.replacementController = replacementController;
        this.commands = commands;
        this.events = events;
        this.protocolVersion = this.profile.protocolVersion();
        this.forgeHandshakeTracker = forgeHandshakeTracker;
        this.playerName = initialPlayerName;
        if (initialPlayerName != null && !initialPlayerName.isBlank()) {
            this.identity.playerName(initialPlayerName);
        }
    }

    @Override
    /** Provides channel read. */
    public void channelRead(ChannelHandlerContext context, Object message) {
        if (!backend.isActive()) {
            ReferenceCountUtil.release(message);
            context.close();
            return;
        }
        var outbound = message;
        var forward = true;
        List<CompressionAction> compressionActions = List.of();
        if (message instanceof ByteBuf buffer) {
            var legacyForgeBlockedBefore = legacyForgeHandshakeBlocksServerboundFrames();
            metrics.frontendToBackendBytes(serverName, buffer.readableBytes());
            capturePayloadPrefix(buffer, CompressionDirection.FRONTEND_TO_BACKEND);
            observeLoginStart(context, buffer);
            observeForgeHandshake(context, buffer);
            observeServerboundPluginChannels(context, buffer);
            observePacketTraffic(buffer);
            var compression = observeCompressionFrames(buffer);
            forward = observeCustomPayload(context, buffer) && compression.shouldForward();
            compressionActions = compression.actions();
            if (forward && outbound instanceof ByteBuf outboundBuffer) {
                ByteBuf filtered;
                try {
                    filtered = suppressLegacyForgeRaceFrames(context, outboundBuffer);
                } catch (RuntimeException exception) {
                    ReferenceCountUtil.release(outbound);
                    context.close();
                    closeBackend();
                    return;
                }
                if (filtered == null) {
                    ReferenceCountUtil.release(outbound);
                    context.channel().read();
                    return;
                }
                if (filtered != outboundBuffer) {
                    ReferenceCountUtil.release(outbound);
                    outbound = filtered;
                    buffer = filtered;
                }
            }
            if (forward) {
                var command = observeCommand(context, buffer);
                if (!command.forward()) {
                    ReferenceCountUtil.release(outbound);
                    context.channel().read();
                    return;
                }
                if (command.message() != buffer) {
                    outbound = command.message();
                    ReferenceCountUtil.release(buffer);
                    buffer = command.message();
                }
            }
            if (forward && outbound instanceof ByteBuf outboundBuffer) {
                ByteBuf gated;
                try {
                    gated = gateLegacyForgeHandshakeFrames(context, outboundBuffer, legacyForgeBlockedBefore);
                } catch (RuntimeException exception) {
                    ReferenceCountUtil.release(outbound);
                    context.close();
                    closeBackend();
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
                var rewriteInput = outbound instanceof ByteBuf byteBuf ? byteBuf : buffer;
                var rewrite = compressionRewriteRuntime.rewrite(
                        context.alloc(),
                        metrics,
                        serverName,
                        CompressionDirection.FRONTEND_TO_BACKEND,
                        rewriteInput,
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
            closeBackend();
            return;
        }
        recordBackpressureIfNeeded(backend, CompressionDirection.FRONTEND_TO_BACKEND);
        backend.writeAndFlush(outbound).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                flushQueuedLegacyForgeFramesIfReady(context);
                flushBackendQueuedLegacyForgeFramesIfReady();
                runDeferredBackendReplacementIfReady();
                context.channel().read();
            } else {
                future.channel().close();
                context.close();
            }
        });
    }

    private void flushBackendQueuedLegacyForgeFramesIfReady() {
        var backendRelay = backend.attr(BackendRelayHandler.HANDLER).get();
        if (backendRelay != null) {
            backendRelay.flushQueuedLegacyForgeFramesIfReady();
        }
    }

    @Override
    /** Provides channel inactive. */
    public void channelInactive(ChannelHandlerContext context) {
        closeFrontendSide(true);
        closeBackend();
    }

    @Override
    /** Provides exception caught. */
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        context.close();
        closeFrontendSide(true);
        closeBackend();
    }

    void detachForBackendReplacement() {
        closeFrontendSide(false);
    }

    private void closeFrontendSide(boolean closePlayerSession) {
        if (closed) {
            return;
        }
        closed = true;
        compressionAudit.closeFrontendSampler();
        customPayloadInspection.close();
        loginStartSampler.close();
        if (compressedCustomPayloadInspection != null) {
            compressedCustomPayloadInspection.close();
        }
        packetTrafficSampler.close();
        legacyForgeGateCompressionCodec.close();
        compressionRewriteRuntime.close();
        releaseLegacyForgeGatePending();
        releaseLegacyForgeQueuedFrames();
        releaseLegacyForgeRacePending();
        if (commandInterceptor != null) {
            commandInterceptor.close();
            commandInterceptor = null;
        }
        metrics.forgeHandshakeClosed(serverName, forgePlayerName(), identity.remoteAddress());
        if (closePlayerSession) {
            if (replacementController == null) {
                metrics.playerSessionClosed(playerName);
            } else {
                replacementController.closePlayerSession(playerName);
            }
        }
        if (replacementController == null) {
            metrics.serverConnectionClosed(serverName);
        } else {
            replacementController.closeServerConnection(serverName);
        }
    }

    private void observeForgeHandshake(ChannelHandlerContext context, ByteBuf buffer) {
        if (forgeHandshakeTracker == null) {
            return;
        }
        try {
            var events = compressionAudit.negotiated()
                    ? forgeHandshakeTracker.observeCompressed(
                            MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                            context.alloc(),
                            buffer,
                            compressionAudit.threshold())
                    : forgeHandshakeTracker.observe(MinecraftForgeHandshakeTracker.Direction.SERVERBOUND, buffer);
            for (var event : events) {
                markLegacyForgeClientDetected(event);
                recordForgeHandshakeEvent(context, event);
            }
            recordForgeHandshakeState();
            markDeferredBackendReplacementReady();
        } catch (RuntimeException exception) {
            metrics.packetAnomaly(
                    "forge-handshake-inspection-malformed",
                    remoteAddress(context),
                    serverName,
                    CompressionDirection.FRONTEND_TO_BACKEND.label(),
                    "PLAY",
                    -1,
                    buffer.readableBytes(),
                    -1,
                    exception.getMessage());
        }
    }

    private void observeServerboundPluginChannels(ChannelHandlerContext context, ByteBuf buffer) {
        if (replacementController == null) {
            return;
        }
        try {
            if (compressionAudit.negotiated()) {
                replacementController.observeCompressedServerboundPluginChannels(
                        context.alloc(),
                        buffer,
                        compressionAudit.threshold(),
                        profile);
            } else {
                replacementController.observeServerboundPluginChannels(buffer, profile);
            }
        } catch (RuntimeException exception) {
            metrics.packetAnomaly(
                    "plugin-channel-registry-malformed",
                    remoteAddress(context),
                    serverName,
                    CompressionDirection.FRONTEND_TO_BACKEND.label(),
                    "PLAY",
                    profile.serverboundCustomPayloadPacketId().orElse(-1),
                    buffer.readableBytes(),
                    -1,
                    exception.getMessage());
        }
    }

    private void markLegacyForgeClientDetected(MinecraftForgeHandshakeTracker.Event event) {
        if (replacementController == null || event == null || !event.expected()) {
            return;
        }
        if ("register".equals(event.type()) || "FML|HS".equals(event.channel())) {
            replacementController.legacyForgeClientDetected();
        }
    }

    private String forgePlayerName() {
        return identity.playerName().isBlank() ? playerName : identity.playerName();
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

    private boolean legacyForgeHandshakeBlocksServerboundFrames() {
        return forgeHandshakeTracker != null
                && profile.legacyForgeHandshakeSupported()
                && forgeHandshakeTracker.backendSwitchBlocked();
    }

    private ByteBuf suppressLegacyForgeRaceFrames(ChannelHandlerContext context, ByteBuf buffer) {
        if (!profile.legacyForgeHandshakeSupported()) {
            return buffer;
        }
        if (legacyForgeRacePending != null && legacyForgeRacePending.isReadable()) {
            return suppressLegacyForgeRaceFramesWithPending(context, buffer);
        }
        releaseLegacyForgeRacePending();
        var frames = buffer.slice();
        while (frames.isReadable()) {
            var probe = MinecraftProtocolCodec.probeFrame(frames, maxFrameBytes);
            if (!probe.complete()) {
                return suppressLegacyForgeRaceFramesWithPending(context, buffer);
            }
            var frame = frames.readSlice(probe.totalBytes());
            if (legacyForgeRaceFrame(context, frame)) {
                return suppressLegacyForgeRaceFramesWithPending(context, buffer);
            }
        }
        return buffer;
    }

    private ByteBuf suppressLegacyForgeRaceFramesWithPending(ChannelHandlerContext context, ByteBuf buffer) {
        if (legacyForgeRacePending == null) {
            legacyForgeRacePending = context.alloc().buffer(buffer.readableBytes());
        }
        if (legacyForgeRacePending.readableBytes() + buffer.readableBytes() > maxFrameBytes + 5) {
            releaseLegacyForgeRacePending();
            throw new IllegalStateException("legacy Forge race guard pending frame exceeded maximum size");
        }
        legacyForgeRacePending.writeBytes(buffer, buffer.readerIndex(), buffer.readableBytes());
        var output = context.alloc().buffer(legacyForgeRacePending.readableBytes());
        try {
            while (legacyForgeRacePending.isReadable()) {
                var probe = MinecraftProtocolCodec.probeFrame(legacyForgeRacePending, maxFrameBytes);
                if (!probe.complete()) {
                    break;
                }
                var frame = legacyForgeRacePending.readRetainedSlice(probe.totalBytes());
                try {
                    if (!legacyForgeRaceFrame(context, frame)) {
                        output.writeBytes(frame, frame.readerIndex(), frame.readableBytes());
                    }
                } finally {
                    frame.release();
                }
                legacyForgeRacePending.discardReadBytes();
            }
            if (output.isReadable()) {
                return output;
            }
            output.release();
            return null;
        } catch (RuntimeException exception) {
            output.release();
            releaseLegacyForgeRacePending();
            throw exception;
        }
    }

    private boolean legacyForgeRaceFrame(ChannelHandlerContext context, ByteBuf frame) {
        var packet = legacyForgeGatePacket(context, frame);
        try {
            var packetId = MinecraftProtocolCodec.readVarInt(packet);
            if (profile.serverboundCustomPayloadPacketId().isEmpty()
                    || packetId != profile.serverboundCustomPayloadPacketId().getAsInt()) {
                return false;
            }
            var channel = MinecraftProtocolCodec.readString(packet, 128);
            var payload = MinecraftCustomPayloadBodyCodec.readBody(
                    packet,
                    profile.serverboundCustomPayloadLengthFormat());
            return "FML".equals(channel)
                    && payload.isReadable()
                    && payload.getUnsignedByte(payload.readerIndex()) == 1;
        } finally {
            packet.release();
        }
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
            if (profile.serverboundCustomPayloadPacketId().isEmpty()
                    || packetId != profile.serverboundCustomPayloadPacketId().getAsInt()) {
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
                return legacyForgeGateCompressionCodec.decodeFrame(
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

    private void flushQueuedLegacyForgeFramesIfReady(ChannelHandlerContext context) {
        if (legacyForgeQueuedFrames == null
                || !legacyForgeQueuedFrames.isReadable()
                || forgeHandshakeTracker == null
                || !forgeHandshakeTracker.complete()
                || !backend.isActive()) {
            return;
        }
        var queued = legacyForgeQueuedFrames;
        legacyForgeQueuedFrames = null;
        backend.writeAndFlush(queued).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                future.channel().close();
                context.close();
            }
        });
    }

    private void recordLegacyForgeGateFailure(String reason, int bytes) {
        metrics.packetAnomaly(
                RULE_LEGACY_FORGE_HANDSHAKE_GATE_MALFORMED,
                identity.remoteAddress(),
                serverName,
                CompressionDirection.FRONTEND_TO_BACKEND.label(),
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

    private void releaseLegacyForgeRacePending() {
        if (legacyForgeRacePending != null && legacyForgeRacePending.refCnt() > 0) {
            legacyForgeRacePending.release();
        }
        legacyForgeRacePending = null;
    }

    private void recordForgeHandshakeEvent(ChannelHandlerContext context, MinecraftForgeHandshakeTracker.Event event) {
        metrics.customPayload(
                serverName,
                CompressionDirection.FRONTEND_TO_BACKEND,
                "FORGE_HANDSHAKE_" + event.type().toUpperCase(java.util.Locale.ROOT),
                event.channel(),
                event.payloadBytes(),
                0,
                identity.playerName(),
                identity.remoteAddress(),
                "PLAY",
                profile.serverboundCustomPayloadPacketId().orElse(-1));
        if (!event.expected()) {
            metrics.packetAnomaly(
                    "forge-handshake-unexpected-order",
                    remoteAddress(context),
                    serverName,
                    CompressionDirection.FRONTEND_TO_BACKEND.label(),
                    "PLAY",
                    profile.serverboundCustomPayloadPacketId().orElse(-1),
                    event.payloadBytes(),
                    -1,
                    event.type() + " stage=" + event.stage());
        }
    }

    private void closeBackend() {
        if (backend.isOpen()) {
            backend.close();
        }
    }

    private CompressionObservation observeCompressionFrames(ByteBuf buffer) {
        if (!compressionAudit.negotiated()) {
            return CompressionObservation.allow();
        }
        try {
            var actions = new ArrayList<CompressionAction>();
            for (var sample : compressionAudit.observeFrontend(buffer)) {
                metrics.compressionSample(
                        serverName,
                        CompressionDirection.FRONTEND_TO_BACKEND,
                        sample.rawBytes(),
                        sample.compressedBytes(),
                        0);
                actions.add(compressionRuntime.recordDecision(
                        metrics,
                        serverName,
                        CompressionDirection.FRONTEND_TO_BACKEND,
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
                    CompressionDirection.FRONTEND_TO_BACKEND.label(),
                    "PLAY",
                    -1,
                    buffer.readableBytes(),
                    -1,
                    exception.getMessage());
            compressionAudit.closeFrontendSampler();
            return CompressionObservation.block();
        }
    }

    private boolean observeCustomPayload(ChannelHandlerContext context, ByteBuf buffer) {
        if (compressionAudit.negotiated()) {
            customPayloadInspection.close();
            return observeCompressedCustomPayload(context, buffer);
        }
        try {
            for (var result : customPayloadInspection.observe(buffer)) {
                recordCustomPayload(result.classification(), -1);
                for (var anomaly : result.anomalies()) {
                    recordCustomPayloadAnomaly(context, result.classification(), anomaly, -1);
                    if (anomaly.action() == AnomalyAction.THROTTLE) {
                        customPayloadInspection.close();
                        return false;
                    }
                }
            }
            return true;
        } catch (RuntimeException exception) {
            metrics.packetAnomaly(
                    "custom-payload-inspection-malformed",
                    remoteAddress(context),
                    serverName,
                    CompressionDirection.FRONTEND_TO_BACKEND.label(),
                    "CONFIGURATION",
                    -1,
                    buffer.readableBytes(),
                    -1,
                    exception.getMessage());
            customPayloadInspection.close();
            return true;
        }
    }

    private boolean observeCompressedCustomPayload(ChannelHandlerContext context, ByteBuf buffer) {
        if (compressedCustomPayloadInspection == null) {
            compressedCustomPayloadInspection = new MinecraftCompressedCustomPayloadInspectionSampler(
                    compressionAudit.threshold(),
                    maxFrameBytes,
                    Math.min(maxFrameBytes, customPayloadPolicy.largePayloadWarnBytes()),
                    customPayloadPolicy);
        }
        try {
            for (var result : compressedCustomPayloadInspection.observe(buffer)) {
                recordCustomPayload(result.classification(), result.compressedBytes());
                for (var anomaly : result.anomalies()) {
                    recordCustomPayloadAnomaly(context, result.classification(), anomaly, result.compressedBytes());
                    if (anomaly.action() == AnomalyAction.THROTTLE) {
                        compressedCustomPayloadInspection.close();
                        return false;
                    }
                }
            }
            return true;
        } catch (RuntimeException exception) {
            metrics.packetAnomaly(
                    "compressed-custom-payload-inspection-malformed",
                    remoteAddress(context),
                    serverName,
                    CompressionDirection.FRONTEND_TO_BACKEND.label(),
                    "CONFIGURATION",
                    -1,
                    buffer.readableBytes(),
                    -1,
                    exception.getMessage());
            compressedCustomPayloadInspection.close();
            return true;
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
                        CompressionDirection.FRONTEND_TO_BACKEND,
                        "UNCOMPRESSED",
                        sample.packetId(),
                        sample.frameBytes(),
                        0);
            }
        } catch (RuntimeException exception) {
            packetTrafficSampler.close();
        }
    }

    private GameCommandFrameInterceptor.Interception observeCommand(ChannelHandlerContext context, ByteBuf buffer) {
        if (commands == null || playerName == null || playerName.isBlank()) {
            return GameCommandFrameInterceptor.Interception.forward(buffer);
        }
        if (commandInterceptor == null) {
            commandInterceptor = new GameCommandFrameInterceptor(commands, events, maxFrameBytes, profile);
        }
        return commandInterceptor.intercept(
                context.alloc(),
                buffer,
                compressionAudit.negotiated(),
                compressionAudit.threshold(),
                new PlayerSource(context.channel(), playerName, profile, compressionAudit));
    }

    private void observeLoginStart(ChannelHandlerContext context, ByteBuf buffer) {
        if (playerName != null || compressionAudit.negotiated()) {
            loginStartSampler.close();
            return;
        }
        try {
            var username = loginStartSampler.observe(buffer);
            if (username.isPresent()) {
                playerName = username.get();
                identity.playerName(playerName);
                metrics.playerSessionStarted(playerName, serverName, remoteAddress(context));
                if (replacementController != null) {
                    replacementController.playerNameDiscovered(playerName);
                }
            }
        } catch (RuntimeException exception) {
            loginStartSampler.close();
        }
    }

    private void recordCustomPayloadAnomaly(
            ChannelHandlerContext context,
            dev.strataproxy.codec.minecraft.MinecraftCustomPayloadClassifier.CustomPayloadClassification classification,
            PacketAnomaly anomaly,
            long compressedSize) {
        metrics.packetAnomaly(
                anomaly.ruleId(),
                remoteAddress(context),
                serverName,
                CompressionDirection.FRONTEND_TO_BACKEND.label(),
                "CONFIGURATION",
                classification.packetId(),
                anomaly.packet().rawSize(),
                compressedSize,
                classification.channel() + " kind=" + classification.kind() + " action=" + anomaly.action());
    }

    private void recordCustomPayload(
            dev.strataproxy.codec.minecraft.MinecraftCustomPayloadClassifier.CustomPayloadClassification classification,
            long compressedSize) {
        metrics.customPayload(
                serverName,
                CompressionDirection.FRONTEND_TO_BACKEND,
                classification.kind().name(),
                classification.channel(),
                classification.payloadBytes(),
                Math.max(0, compressedSize),
                identity.playerName(),
                identity.remoteAddress(),
                "CONFIGURATION",
                classification.packetId());
    }

    private void recordBackpressureIfNeeded(Channel target, CompressionDirection direction) {
        if (!target.isWritable()) {
            metrics.relayBackpressure(serverName, direction, target.bytesBeforeWritable());
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

    private static String remoteAddress(ChannelHandlerContext context) {
        return ClientAddress.text(context.channel());
    }

    private static final class PlayerSource implements CommandSource {
        private final Channel frontend;
        private final String name;
        private final MinecraftProtocolProfile profile;
        private final MinecraftCompressionAuditState compressionAudit;

        private PlayerSource(
                Channel frontend,
                String name,
                MinecraftProtocolProfile profile,
                MinecraftCompressionAuditState compressionAudit) {
            this.frontend = frontend;
            this.name = name == null ? "" : name;
            this.profile = profile == null
                    ? MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_20_1)
                    : profile;
            this.compressionAudit = compressionAudit;
        }

        @Override
        /** Provides name. */
        public String name() {
            return name;
        }

        @Override
        /** Provides send message. */
        public void sendMessage(String message) {
            var write = (Runnable) () -> {
                var frame = MinecraftPlayMessages.systemChatFrame(
                        frontend.alloc(),
                        profile,
                        message,
                        compressionAudit.negotiated(),
                        compressionAudit.negotiated() ? compressionAudit.threshold() : 0);
                if (frame.isEmpty()) {
                    return;
                }
                var response = frame.get();
                if (!frontend.isActive()) {
                    response.release();
                    return;
                }
                frontend.writeAndFlush(response);
            };
            try {
                if (frontend.eventLoop().inEventLoop()) {
                    write.run();
                } else {
                    frontend.eventLoop().execute(write);
                }
            } catch (RuntimeException exception) {
                // The client is already disconnecting or the event loop is shutting down.
            }
        }
    }

    private static double ratio(MinecraftCompressedFrameAuditSampler.CompressionFrameSample sample) {
        return sample.rawBytes() == 0 ? 1.0d : (double) sample.compressedBytes() / sample.rawBytes();
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

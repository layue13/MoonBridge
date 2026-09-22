package dev.strataproxy.network;

import dev.strataproxy.compression.CompressionAction;
import dev.strataproxy.plugin.command.CommandRegistry;
import dev.strataproxy.plugin.command.CommandSource;
import dev.strataproxy.plugin.event.EventBus;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

import java.util.List;

final class FrontendRelayHandler extends ChannelInboundHandlerAdapter {
    private static final String RULE_COMPRESSION_AUDIT_MALFORMED = "compression-audit-malformed";
    private static final String RULE_LEGACY_FORGE_HANDSHAKE_GATE_MALFORMED = "legacy-forge-handshake-gate-malformed";
    private static final int MAX_LEGACY_FORGE_QUEUED_BYTES = 1_048_576;

    private final Channel backend;
    private final ProxyMetrics metrics;
    private final ProxyMetrics.ServerTrafficRecorder trafficRecorder;
    private final String serverName;
    private final MinecraftCompressionAuditState compressionAudit;
    private final CompressionRuntime compressionRuntime;
    private final MinecraftLoginStartSampler loginStartSampler;
    private final MinecraftCompressionCodec legacyForgeGateCompressionCodec = new MinecraftCompressionCodec();
    private final int maxFrameBytes;
    private final RelaySessionIdentity identity;
    private final CompressionRewriteRuntime compressionRewriteRuntime;
    private final BackendReplacementController replacementController;
    private final CommandRegistry commands;
    private final EventBus events;
    private final int protocolVersion;
    private final MinecraftProtocolProfile profile;
    private final MinecraftForgeHandshakeTracker forgeHandshakeTracker;
    private GameCommandFrameInterceptor commandInterceptor;
    private ByteBuf legacyForgeRacePending;
    private ByteBuf legacyForgeGatePending;
    private ByteBuf legacyForgeQueuedFrames;
    private String playerName;
    private boolean deferredBackendReplacementReady;
    private boolean closed;

    FrontendRelayHandler(
            Channel backend,
            ProxyMetrics metrics,
            String serverName,
            MinecraftCompressionAuditState compressionAudit,
            CompressionRuntime compressionRuntime,
            int maxFrameBytes,
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
        this.trafficRecorder = metrics.serverTrafficRecorder(serverName);
        this.serverName = serverName;
        this.compressionAudit = compressionAudit;
        this.compressionRuntime = compressionRuntime;
        this.maxFrameBytes = maxFrameBytes;
        this.profile = profile == null
                ? MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_20_1)
                : profile;
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
            trafficRecorder.frontendToBackendBytes(buffer.readableBytes());
            observeLoginStart(context, buffer);
            observeForgeHandshake(context, buffer);
            observeServerboundPluginChannels(context, buffer);
            var compression = observeCompressionFrames(buffer);
            forward = compression.shouldForward();
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
        loginStartSampler.close();
        legacyForgeGateCompressionCodec.close();
        compressionRewriteRuntime.close();
        releaseLegacyForgeGatePending();
        releaseLegacyForgeQueuedFrames();
        releaseLegacyForgeRacePending();
        if (commandInterceptor != null) {
            commandInterceptor.close();
            commandInterceptor = null;
        }
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
            }
            markDeferredBackendReplacementReady();
        } catch (RuntimeException ignored) {
            // Handshake observation must not affect transparent forwarding.
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
        } catch (RuntimeException ignored) {
            // Channel observation must not affect transparent forwarding.
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

    private void closeBackend() {
        if (backend.isOpen()) {
            backend.close();
        }
    }

    private RelayCompressionObserver.Observation observeCompressionFrames(ByteBuf buffer) {
        return RelayCompressionObserver.observe(
                compressionAudit, buffer, false, compressionRuntime, metrics);
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
                metrics.playerSessionStarted(playerName, identity.playerId(), identity.connectionId(), serverName, remoteAddress(context));
                if (replacementController != null) {
                    replacementController.playerNameDiscovered(playerName);
                }
            }
        } catch (RuntimeException exception) {
            loginStartSampler.close();
        }
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

}

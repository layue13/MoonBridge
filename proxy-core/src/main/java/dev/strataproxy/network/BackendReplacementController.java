package dev.strataproxy.network;

import dev.strataproxy.plugin.command.CommandRegistry;
import dev.strataproxy.plugin.event.EventBus;
import dev.strataproxy.plugin.event.PlayerTransferEvent;
import dev.strataproxy.plugin.service.PlayerIdentity;
import dev.strataproxy.plugin.service.PlayerTransfer;
import dev.strataproxy.network.MinecraftCompressionCodec;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.util.AttributeKey;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

final class BackendReplacementController {
    static final AttributeKey<Boolean> MIGRATING_BACKEND = AttributeKey.valueOf("strataproxy.migratingBackend");
    private static final long FORGE_DEFERRED_TRANSFER_TIMEOUT_MILLIS = 30_000L;

    private final ServerTargetResolver targetResolver;
    private final BackendConnector backendConnector;
    private final ProxyMetrics metrics;
    private final NetworkTuning tuning;
    private final CompressionRuntime compressionRuntime;
    private final RelaySession session;
    private final RelaySessionRegistry sessions;
    private final CommandRegistry commands;
    private final EventBus events;
    private final MinecraftProtocolProfile profile;
    private final boolean compressionRewriteEnabled;
    private final int compressionRewriteMaxEventLoopDelayMillis;

    BackendReplacementController(
            ServerTargetResolver targetResolver,
            BackendConnector backendConnector,
            ProxyMetrics metrics,
            NetworkTuning tuning,
            CompressionRuntime compressionRuntime,
            RelaySession session,
            RelaySessionRegistry sessions,
            boolean compressionRewriteEnabled,
            int compressionRewriteMaxEventLoopDelayMillis,
            CommandRegistry commands,
            EventBus events,
            int protocolVersion) {
        this(
                targetResolver,
                backendConnector,
                metrics,
                tuning,
                compressionRuntime,
                session,
                sessions,
                compressionRewriteEnabled,
                compressionRewriteMaxEventLoopDelayMillis,
                commands,
                events,
                MinecraftProtocolProfile.forVersion(protocolVersion));
    }

    BackendReplacementController(
            ServerTargetResolver targetResolver,
            BackendConnector backendConnector,
            ProxyMetrics metrics,
            NetworkTuning tuning,
            CompressionRuntime compressionRuntime,
            RelaySession session,
            RelaySessionRegistry sessions,
            boolean compressionRewriteEnabled,
            int compressionRewriteMaxEventLoopDelayMillis,
            CommandRegistry commands,
            EventBus events,
            MinecraftProtocolProfile profile) {
        this.targetResolver = targetResolver == null ? ServerTargetResolver.unavailable() : targetResolver;
        this.backendConnector = backendConnector;
        this.metrics = metrics;
        this.tuning = tuning;
        this.compressionRuntime = compressionRuntime;
        this.session = session;
        this.sessions = sessions;
        this.commands = commands;
        this.events = events;
        this.profile = profile == null
                ? MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_20_1)
                : profile;
        this.compressionRewriteEnabled = compressionRewriteEnabled;
        this.compressionRewriteMaxEventLoopDelayMillis = compressionRewriteMaxEventLoopDelayMillis;
        this.session.replacementController(this);
    }

    CompletionStage<PlayerTransferResult> replaceBackend(String targetServerName) {
        var result = new CompletableFuture<PlayerTransferResult>();
        var frontend = session.frontend();
        if (frontend == null || !frontend.isActive()) {
            metrics.backendReplacement("inactive_session");
            completeTransfer(result, PlayerTransferResult.failure(
                    "inactive_session",
                    session.identity().playerName(),
                    session.serverName(),
                    targetServerName));
            return result;
        }
        var task = (Runnable) () -> replaceBackend(frontend, session.backend(), session.serverName(), targetServerName, result);
        if (frontend.eventLoop().inEventLoop()) {
            task.run();
        } else {
            try {
                frontend.eventLoop().execute(task);
            } catch (RuntimeException exception) {
                completeTransfer(result, PlayerTransferResult.failure(
                        "event_loop_unavailable",
                        session.identity().playerName(),
                        session.serverName(),
                        targetServerName));
            }
        }
        return result;
    }

    private void replaceBackend(
            Channel frontend,
            Channel oldBackend,
            String currentServerName,
            String targetServerName,
            CompletableFuture<PlayerTransferResult> result) {
        if (targetServerName == null || targetServerName.isBlank()) {
            metrics.backendReplacement("invalid_target");
            completeTransfer(result, failure("invalid_target", currentServerName, targetServerName));
            return;
        }
        if (!profile.backendReplacementSupported()) {
            metrics.backendReplacement("unsupported_protocol_switch");
            completeTransfer(result, failure("unsupported_protocol_switch", currentServerName, targetServerName));
            return;
        }
        if (!session.beginBackendReplacement()) {
            metrics.backendReplacement("busy");
            completeTransfer(result, failure("busy", currentServerName, targetServerName));
            return;
        }
        metrics.backendReplacement("attempted");
        var selected = target(targetServerName);
        if (selected == null) {
            session.finishBackendReplacement();
            metrics.failedRoute();
            metrics.backendReplacement("target_unavailable");
            completeTransfer(result, failure("target_unavailable", currentServerName, targetServerName));
            return;
        }

        var nextServerName = selected.descriptor().name();
        if (nextServerName.equalsIgnoreCase(currentServerName)) {
            session.finishBackendReplacement();
            metrics.backendReplacement("same_server");
            completeTransfer(result, failure("same_server", currentServerName, nextServerName));
            return;
        }
        var forgeHandshakeTracker = session.forgeHandshakeTracker();
        if (forgeHandshakeTracker != null && forgeHandshakeTracker.backendSwitchBlocked()) {
            session.finishBackendReplacement();
            var pending = session.deferBackendTransfer(nextServerName, result);
            if (pending != null) {
                metrics.backendReplacement("forge_handshake_deferred");
                metrics.playerTransfer(
                        false,
                        "forge_handshake_deferred",
                        session.identity().playerName(),
                        currentServerName,
                        nextServerName,
                        session.identity().remoteAddress());
                scheduleDeferredTransferTimeout(frontend, pending);
            } else {
                metrics.backendReplacement("forge_handshake_transfer_pending");
                completeTransfer(result, failure("forge_handshake_transfer_pending", currentServerName, nextServerName));
            }
            return;
        }
        var loginSession = session.loginSession();
        if (loginSession == null || !loginSession.available()) {
            session.finishBackendReplacement();
            metrics.backendReplacement("missing_login_session");
            completeTransfer(result, failure("missing_login_session", currentServerName, nextServerName));
            return;
        }
        var resetClientForgeHandshake = forgeHandshakeTracker != null && forgeHandshakeTracker.complete();
        var forgeTrackerSwap = session.beginForgeHandshakeTrackerSwap(tuning.maxFrameBytes(), profile);
        var nextCompressionAudit = new MinecraftCompressionAuditState(tuning.maxFrameBytes());
        frontend.config().setAutoRead(false);
        pauseOldBackend(oldBackend);
        var listener = new BackendSwitchLoginHandler.Listener() {
            @Override
            /** Provides backend login ready. */
            public void backendLoginReady(Channel nextBackend, io.netty.buffer.ByteBuf clientboundFrames, io.netty.buffer.ByteBuf remainingBackendFrames) {
                completeReplacement(
                        frontend,
                        oldBackend,
                        currentServerName,
                        nextServerName,
                        nextBackend,
                        nextCompressionAudit,
                        clientboundFrames,
                        remainingBackendFrames,
                        resetClientForgeHandshake,
                        forgeTrackerSwap,
                        result);
            }

            @Override
            /** Provides backend login failed. */
            public void backendLoginFailed(Channel nextBackend, String outcome) {
                metrics.backendReplacement(outcome);
                session.finishBackendReplacement();
                if (nextBackend != null && nextBackend.isOpen()) {
                    nextBackend.close();
                }
                session.rollbackForgeHandshakeTrackerSwap(forgeTrackerSwap);
                resumeOldRelay(frontend, oldBackend);
                completeTransfer(result, failure(outcome, currentServerName, nextServerName));
            }
        };
        try {
            backendConnector.connectForSwitchLogin(frontend, selected, nextCompressionAudit, session, forgeTrackerSwap.next(), listener).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                metrics.backendConnectFailure();
                metrics.backendReplacement("connect_failure");
                session.finishBackendReplacement();
                session.rollbackForgeHandshakeTrackerSwap(forgeTrackerSwap);
                resumeOldRelay(frontend, oldBackend);
                completeTransfer(result, failure("connect_failure", currentServerName, nextServerName));
                return;
            }

            var nextBackend = future.channel();
            try {
                nextBackend.config().setAutoRead(false);
                loginSession.writeTo(nextBackend);
                nextBackend.read();
            } catch (RuntimeException exception) {
                metrics.backendReplacement("backend_login_write_failure");
                session.finishBackendReplacement();
                if (nextBackend.isOpen()) {
                    nextBackend.close();
                }
                session.rollbackForgeHandshakeTrackerSwap(forgeTrackerSwap);
                resumeOldRelay(frontend, oldBackend);
                completeTransfer(result, failure("backend_login_write_failure", currentServerName, nextServerName));
            }
            });
        } catch (RuntimeException exception) {
            metrics.backendConnectFailure();
            metrics.backendReplacement("connect_failure");
            session.finishBackendReplacement();
            session.rollbackForgeHandshakeTrackerSwap(forgeTrackerSwap);
            resumeOldRelay(frontend, oldBackend);
            completeTransfer(result, failure("connect_failure", currentServerName, nextServerName));
        }
    }

    void completeReplacement(
            Channel frontend,
            Channel oldBackend,
            String currentServerName,
            String nextServerName,
            Channel nextBackend,
            MinecraftCompressionAuditState nextCompressionAudit,
            io.netty.buffer.ByteBuf clientboundFrames,
            io.netty.buffer.ByteBuf remainingBackendFrames,
            boolean resetClientForgeHandshake,
            RelaySession.ForgeHandshakeTrackerSwap forgeTrackerSwap,
            CompletableFuture<PlayerTransferResult> result) {
        if (result.isDone()) {
            release(clientboundFrames);
            release(remainingBackendFrames);
            session.rollbackForgeHandshakeTrackerSwap(forgeTrackerSwap);
            return;
        }
        var initialClientbound = prependForgeResetIfNeeded(frontend, clientboundFrames, resetClientForgeHandshake, nextCompressionAudit);
        initialClientbound = prependLegacyClientStateResetIfNeeded(frontend, initialClientbound, nextCompressionAudit);
        var initialBackend = remainingBackendFrames;
        try {
            var identity = session.identity();
            var forgeHandshakeTracker = session.forgeHandshakeTracker();
            var backendRelay = new BackendRelayHandler(
                    frontend,
                    metrics,
                    nextServerName,
                    tuning.maxFrameBytes(),
                    nextCompressionAudit,
                    compressionRuntime,
                    identity,
                    MinecraftForwardingRuntime.none(),
                    compressionRewriteEnabled,
                    compressionRewriteMaxEventLoopDelayMillis,
                    profile,
                    this,
                    forgeHandshakeTracker,
                    profile.backendSwitchStrategy(session.legacyForgeClientDetected()));
            if (nextBackend.pipeline().context("backend-switch-login") != null) {
                nextBackend.pipeline().replace("backend-switch-login", "backend-relay", backendRelay);
            } else {
                nextBackend.pipeline().addLast("backend-relay", backendRelay);
            }
            var nextRelay = new FrontendRelayHandler(
                    nextBackend,
                    metrics,
                    nextServerName,
                    nextCompressionAudit,
                    compressionRuntime,
                    tuning.maxFrameBytes(),
                    identity.playerName().isBlank() ? null : identity.playerName(),
                    identity,
                    compressionRewriteEnabled,
                    compressionRewriteMaxEventLoopDelayMillis,
                    this,
                    commands,
                    events,
                    profile,
                    null,
                    forgeHandshakeTracker);
            var frontendPipeline = frontend.pipeline();
            var oldRelay = session.frontendRelay();
            if (oldRelay != null) {
                if (frontendPipeline.context(oldRelay) != null) {
                    frontendPipeline.replace(oldRelay, "frontend-relay", nextRelay);
                } else if (frontendPipeline.context("initial-handshake-route") != null) {
                    frontendPipeline.replace("initial-handshake-route", "frontend-relay", nextRelay);
                } else {
                    frontendPipeline.addLast("frontend-relay", nextRelay);
                }
                oldRelay.detachForBackendReplacement();
            } else {
                frontendPipeline.addLast("frontend-relay", nextRelay);
            }
            relayAttached(nextRelay, frontend, nextBackend, nextServerName);

            if (initialClientbound != null && initialClientbound.isReadable()) {
                var pendingBackendFrames = initialBackend;
                initialBackend = null;
                frontend.writeAndFlush(initialClientbound).addListener((ChannelFutureListener) future -> {
                    if (future.isSuccess()) {
                        activateReplacementAfterInitialClientbound(
                                frontend,
                                oldBackend,
                                currentServerName,
                                nextServerName,
                                nextBackend,
                                pendingBackendFrames,
                                forgeTrackerSwap,
                                result);
                    } else {
                        release(pendingBackendFrames);
                        failReplacementAfterPipelineAttached(
                                frontend,
                                oldBackend,
                                nextBackend,
                                forgeTrackerSwap,
                                result,
                                "initial_clientbound_write_failure",
                                currentServerName,
                                nextServerName);
                    }
                });
                initialClientbound = null;
            } else {
                release(initialClientbound);
                initialClientbound = null;
                var pendingBackendFrames = initialBackend;
                initialBackend = null;
                activateReplacementAfterInitialClientbound(
                        frontend,
                        oldBackend,
                        currentServerName,
                        nextServerName,
                        nextBackend,
                        pendingBackendFrames,
                        forgeTrackerSwap,
                        result);
            }
        } catch (RuntimeException exception) {
            release(initialClientbound);
            release(initialBackend);
            metrics.backendReplacement("pipeline_failure");
            session.finishBackendReplacement();
            if (nextBackend.isOpen()) {
                nextBackend.close();
            }
            closeOldBackend(oldBackend);
            closeFrontend(frontend);
            session.rollbackForgeHandshakeTrackerSwap(forgeTrackerSwap);
            completeTransfer(result, failure("pipeline_failure", currentServerName, nextServerName));
        }
    }

    private void activateReplacementAfterInitialClientbound(
            Channel frontend,
            Channel oldBackend,
            String currentServerName,
            String nextServerName,
            Channel nextBackend,
            io.netty.buffer.ByteBuf initialBackend,
            RelaySession.ForgeHandshakeTrackerSwap forgeTrackerSwap,
            CompletableFuture<PlayerTransferResult> result) {
        try {
            var identity = session.identity();
            if (initialBackend != null && initialBackend.isReadable()) {
                nextBackend.pipeline().fireChannelRead(initialBackend);
                initialBackend = null;
            } else {
                release(initialBackend);
                initialBackend = null;
            }
            closeOldBackend(oldBackend);
            session.finishBackendReplacement();
            frontend.config().setAutoRead(false);
            nextBackend.config().setAutoRead(false);
            frontend.read();
            nextBackend.read();
            metrics.backendReplacement("success");
            metrics.routedConnection();
            metrics.serverConnectionOpened(nextServerName);
            if (!identity.playerName().isBlank()) {
                metrics.playerSessionStarted(identity.playerName(), identity.playerId(), identity.connectionId(), nextServerName, identity.remoteAddress());
            }
            session.commitForgeHandshakeTrackerSwap(forgeTrackerSwap);
            completeTransfer(result, PlayerTransferResult.success(identity.playerName(), currentServerName, nextServerName));
        } catch (RuntimeException exception) {
            release(initialBackend);
            failReplacementAfterPipelineAttached(
                    frontend,
                    oldBackend,
                    nextBackend,
                    forgeTrackerSwap,
                    result,
                    "pipeline_failure",
                    currentServerName,
                    nextServerName);
        }
    }

    private void failReplacementAfterPipelineAttached(
            Channel frontend,
            Channel oldBackend,
            Channel nextBackend,
            RelaySession.ForgeHandshakeTrackerSwap forgeTrackerSwap,
            CompletableFuture<PlayerTransferResult> result,
            String outcome,
            String currentServerName,
            String nextServerName) {
        if (result.isDone()) {
            return;
        }
        metrics.backendReplacement(outcome);
        session.finishBackendReplacement();
        if (nextBackend.isOpen()) {
            nextBackend.close();
        }
        closeOldBackend(oldBackend);
        closeFrontend(frontend);
        session.rollbackForgeHandshakeTrackerSwap(forgeTrackerSwap);
        completeTransfer(result, failure(outcome, currentServerName, nextServerName));
    }

    void relayAttached(FrontendRelayHandler handler, Channel frontend, Channel backend, String serverName) {
        session.attach(frontend, backend, serverName);
        session.frontendRelay(handler);
        sessions.register(session);
    }

    void playerNameDiscovered(String playerName) {
        if (playerName != null && !playerName.isBlank()) {
            sessions.register(session);
        }
    }

    void legacyForgeClientDetected() {
        session.legacyForgeClientDetected(profile.legacyForgeHandshakeSupported());
    }

    void closeServerConnection(String serverName) {
        metrics.serverConnectionClosed(serverName);
    }

    void closePlayerSession(String playerName) {
        var pendingFailure = session.failPendingBackendTransfer("player_session_closed");
        if (pendingFailure != null) {
            metrics.playerTransfer(
                    pendingFailure.success(),
                    pendingFailure.outcome(),
                    pendingFailure.player(),
                    pendingFailure.sourceServer(),
                    pendingFailure.targetServer(),
                    session.identity().remoteAddress());
        }
        session.closeClientStateTracker();
        session.closePluginChannelRegistry();
        sessions.unregister(session);
        metrics.playerSessionClosed(playerName, session.identity().connectionId());
    }

    void observeServerboundPluginChannels(io.netty.buffer.ByteBuf buffer, MinecraftProtocolProfile profile) {
        session.pluginChannelRegistry().observeServerbound(buffer, profile);
    }

    void observeCompressedServerboundPluginChannels(
            io.netty.buffer.ByteBufAllocator allocator,
            io.netty.buffer.ByteBuf buffer,
            int threshold,
            MinecraftProtocolProfile profile) {
        session.pluginChannelRegistry().observeServerboundCompressed(allocator, buffer, threshold, profile);
    }

    void replayServerboundPluginChannels(Channel backend) {
        replayServerboundPluginChannels(backend, null);
    }

    void replayServerboundPluginChannels(Channel backend, MinecraftCompressionAuditState compressionAudit) {
        if (backend == null || !backend.isActive()) {
            return;
        }
        var frame = session.pluginChannelRegistry().registrationFrame(backend.alloc(), profile);
        if (frame == null || !frame.isReadable()) {
            release(frame);
            return;
        }
        if (compressionAudit != null && compressionAudit.negotiated()) {
            frame = compressedFrameBatch(backend, frame, compressionAudit);
        }
        backend.writeAndFlush(frame);
    }

    void observeLegacyClientState(io.netty.buffer.ByteBuf buffer, MinecraftProtocolProfile profile) {
        session.clientStateTracker().observe(buffer, profile);
    }

    void observeCompressedLegacyClientState(
            io.netty.buffer.ByteBufAllocator allocator,
            io.netty.buffer.ByteBuf buffer,
            int threshold,
            MinecraftProtocolProfile profile) {
        session.clientStateTracker().observeCompressed(allocator, buffer, threshold, profile);
    }

    void runDeferredBackendReplacementIfReady() {
        var tracker = session.forgeHandshakeTracker();
        if (tracker != null && tracker.backendSwitchBlocked()) {
            return;
        }
        var pending = session.consumePendingBackendTransfer();
        if (pending == null) {
            return;
        }
        replaceBackend(pending.targetServerName()).whenComplete((result, exception) -> {
            if (exception != null) {
                completeTransfer(pending.result(), PlayerTransferResult.failure(
                        "deferred_transfer_exception",
                        session.identity().playerName(),
                        session.serverName(),
                        pending.targetServerName()));
            } else {
                pending.result().complete(result);
            }
        });
    }

    private void scheduleDeferredTransferTimeout(Channel frontend, RelaySession.PendingBackendTransfer pending) {
        frontend.eventLoop().schedule(() -> {
            if (!session.consumePendingBackendTransfer(pending)) {
                return;
            }
            metrics.backendReplacement("forge_handshake_deferred_timeout");
            completeTransfer(pending.result(), PlayerTransferResult.failure(
                    "forge_handshake_deferred_timeout",
                    session.identity().playerName(),
                    session.serverName(),
                    pending.targetServerName()));
        }, FORGE_DEFERRED_TRANSFER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    private PlayerTransferResult failure(String outcome, String sourceServer, String targetServer) {
        return PlayerTransferResult.failure(outcome, session.identity().playerName(), sourceServer, targetServer);
    }

    private void completeTransfer(CompletableFuture<PlayerTransferResult> result, PlayerTransferResult transfer) {
        metrics.playerTransfer(
                transfer.success(),
                transfer.outcome(),
                transfer.player(),
                session.identity().connectionId(),
                transfer.sourceServer(),
                transfer.targetServer(),
                session.identity().remoteAddress());
        var identity = session.identity();
        var stage = transfer.success() ? PlayerTransfer.TransferStage.NETWORK_READY
                : "player_session_closed".equals(transfer.outcome()) ? PlayerTransfer.TransferStage.DISCONNECTED
                : PlayerTransfer.TransferStage.REJECTED;
        if (events != null) {
            events.publish(new PlayerTransferEvent(
                    new PlayerIdentity(identity.playerId(), identity.connectionId()), transfer.player(), transfer.sourceServer(),
                    transfer.targetServer(), transfer.success(), transfer.outcome(), stage, !transfer.success(),
                    transfer.success() ? transfer.targetServer() : session.serverName()));
        }
        result.complete(transfer);
    }

    private dev.strataproxy.api.server.RegisteredServer target(String targetServerName) {
        try {
            return targetResolver.resolveTarget(targetServerName.trim(), profile.protocolVersion()).orElse(null);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static void resumeOldRelay(Channel frontend, Channel oldBackend) {
        if (frontend.isActive()) {
            frontend.config().setAutoRead(false);
            frontend.read();
        }
        if (oldBackend != null && oldBackend.isActive()) {
            oldBackend.read();
        }
    }

    private static void pauseOldBackend(Channel oldBackend) {
        if (oldBackend != null && oldBackend.isActive()) {
            oldBackend.config().setAutoRead(false);
        }
    }

    private static void closeOldBackend(Channel oldBackend) {
        if (oldBackend != null && oldBackend.isOpen()) {
            oldBackend.attr(MIGRATING_BACKEND).set(true);
            oldBackend.close();
        }
    }

    private static void closeFrontend(Channel frontend) {
        if (frontend != null && frontend.isOpen()) {
            frontend.close();
        }
    }

    private static void release(io.netty.buffer.ByteBuf buffer) {
        if (buffer != null && buffer.refCnt() > 0) {
            buffer.release();
        }
    }

    io.netty.buffer.ByteBuf prependForgeResetIfNeeded(
            Channel frontend,
            io.netty.buffer.ByteBuf clientboundFrames,
            boolean resetClientForgeHandshake) {
        return prependForgeResetIfNeeded(frontend, clientboundFrames, resetClientForgeHandshake, null);
    }

    io.netty.buffer.ByteBuf prependForgeResetIfNeeded(
            Channel frontend,
            io.netty.buffer.ByteBuf clientboundFrames,
            boolean resetClientForgeHandshake,
            MinecraftCompressionAuditState compressionAudit) {
        if (!resetClientForgeHandshake || !profile.legacyForgeHandshakeSupported()) {
            return clientboundFrames;
        }
        var reset = MinecraftLegacyTransferPackets.forgeHandshakeResetFrame(frontend.alloc(), profile);
        if (compressionAudit != null && compressionAudit.negotiated()) {
            reset = compressedClientboundFrames(frontend, reset, compressionAudit);
        }
        if (clientboundFrames == null || !clientboundFrames.isReadable()) {
            release(clientboundFrames);
            return reset;
        }
        var combined = frontend.alloc().buffer(reset.readableBytes() + clientboundFrames.readableBytes());
        try {
            combined.writeBytes(reset, reset.readerIndex(), reset.readableBytes());
            combined.writeBytes(clientboundFrames, clientboundFrames.readerIndex(), clientboundFrames.readableBytes());
            return combined;
        } finally {
            reset.release();
            clientboundFrames.release();
        }
    }

    io.netty.buffer.ByteBuf prependLegacyClientStateResetIfNeeded(
            Channel frontend,
            io.netty.buffer.ByteBuf clientboundFrames) {
        return prependLegacyClientStateResetIfNeeded(frontend, clientboundFrames, null);
    }

    io.netty.buffer.ByteBuf prependLegacyClientStateResetIfNeeded(
            Channel frontend,
            io.netty.buffer.ByteBuf clientboundFrames,
            MinecraftCompressionAuditState compressionAudit) {
        var reset = session.clientStateTracker().clearFrames(frontend.alloc(), profile);
        if (reset == null || !reset.isReadable()) {
            release(reset);
            return clientboundFrames;
        }
        if (compressionAudit != null && compressionAudit.negotiated()) {
            reset = compressedClientboundFrames(frontend, reset, compressionAudit);
        }
        if (clientboundFrames == null || !clientboundFrames.isReadable()) {
            release(clientboundFrames);
            return reset;
        }
        var combined = frontend.alloc().buffer(reset.readableBytes() + clientboundFrames.readableBytes());
        try {
            combined.writeBytes(reset, reset.readerIndex(), reset.readableBytes());
            combined.writeBytes(clientboundFrames, clientboundFrames.readerIndex(), clientboundFrames.readableBytes());
            return combined;
        } finally {
            reset.release();
            clientboundFrames.release();
        }
    }

    private io.netty.buffer.ByteBuf compressedClientboundFrames(
            Channel frontend,
            io.netty.buffer.ByteBuf frames,
            MinecraftCompressionAuditState compressionAudit) {
        return compressedFrameBatch(frontend, frames, compressionAudit);
    }

    private io.netty.buffer.ByteBuf compressedFrameBatch(
            Channel channel,
            io.netty.buffer.ByteBuf frames,
            MinecraftCompressionAuditState compressionAudit) {
        try (var codec = new MinecraftCompressionCodec()) {
            return MinecraftLegacyTransferPackets.compressFrameBatch(
                    channel.alloc(),
                    frames,
                    codec,
                    compressionAudit.threshold(),
                    tuning.maxFrameBytes());
        } finally {
            release(frames);
        }
    }
}

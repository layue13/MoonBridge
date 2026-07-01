package dev.strataproxy.network;

import dev.strataproxy.analysis.CustomPayloadAnomalyPolicy;
import dev.strataproxy.observability.ProxyMetrics;
import dev.strataproxy.plugin.command.CommandRegistry;
import dev.strataproxy.plugin.event.EventBus;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.util.AttributeKey;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class BackendReplacementController {
    static final AttributeKey<Boolean> MIGRATING_BACKEND = AttributeKey.valueOf("strataproxy.migratingBackend");

    private final ServerTargetResolver targetResolver;
    private final BackendConnector backendConnector;
    private final ProxyMetrics metrics;
    private final NetworkTuning tuning;
    private final CompressionRuntime compressionRuntime;
    private final CustomPayloadAnomalyPolicy customPayloadPolicy;
    private final RelaySession session;
    private final RelaySessionRegistry sessions;
    private final CommandRegistry commands;
    private final EventBus events;
    private final int protocolVersion;
    private final boolean compressionRewriteEnabled;
    private final int compressionRewriteMaxEventLoopDelayMillis;

    BackendReplacementController(
            ServerTargetResolver targetResolver,
            BackendConnector backendConnector,
            ProxyMetrics metrics,
            NetworkTuning tuning,
            CompressionRuntime compressionRuntime,
            CustomPayloadAnomalyPolicy customPayloadPolicy,
            RelaySession session,
            RelaySessionRegistry sessions,
            boolean compressionRewriteEnabled,
            int compressionRewriteMaxEventLoopDelayMillis,
            CommandRegistry commands,
            EventBus events,
            int protocolVersion) {
        this.targetResolver = targetResolver == null ? ServerTargetResolver.unavailable() : targetResolver;
        this.backendConnector = backendConnector;
        this.metrics = metrics;
        this.tuning = tuning;
        this.compressionRuntime = compressionRuntime;
        this.customPayloadPolicy = customPayloadPolicy;
        this.session = session;
        this.sessions = sessions;
        this.commands = commands;
        this.events = events;
        this.protocolVersion = protocolVersion;
        this.compressionRewriteEnabled = compressionRewriteEnabled;
        this.compressionRewriteMaxEventLoopDelayMillis = compressionRewriteMaxEventLoopDelayMillis;
        this.session.replacementController(this);
    }

    CompletionStage<PlayerTransferResult> replaceBackend(String targetServerName) {
        var result = new CompletableFuture<PlayerTransferResult>();
        var frontend = session.frontend();
        if (frontend == null || !frontend.isActive()) {
            metrics.backendReplacement("inactive_session");
            result.complete(PlayerTransferResult.failure(
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
                result.complete(PlayerTransferResult.failure(
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
            result.complete(failure("invalid_target", currentServerName, targetServerName));
            return;
        }
        if (!session.beginBackendReplacement()) {
            metrics.backendReplacement("busy");
            result.complete(failure("busy", currentServerName, targetServerName));
            return;
        }
        metrics.backendReplacement("attempted");
        var selected = target(targetServerName);
        if (selected == null) {
            session.finishBackendReplacement();
            metrics.failedRoute();
            metrics.backendReplacement("target_unavailable");
            result.complete(failure("target_unavailable", currentServerName, targetServerName));
            return;
        }

        var nextServerName = selected.descriptor().name();
        if (nextServerName.equalsIgnoreCase(currentServerName)) {
            session.finishBackendReplacement();
            metrics.backendReplacement("same_server");
            result.complete(failure("same_server", currentServerName, nextServerName));
            return;
        }
        var loginSession = session.loginSession();
        if (loginSession == null || !loginSession.available()) {
            session.finishBackendReplacement();
            metrics.backendReplacement("missing_login_session");
            result.complete(failure("missing_login_session", currentServerName, nextServerName));
            return;
        }
        var nextCompressionAudit = new MinecraftCompressionAuditState(tuning.maxFrameBytes());
        frontend.config().setAutoRead(false);
        var listener = new BackendSwitchLoginHandler.Listener() {
            @Override
            public void backendLoginReady(Channel nextBackend) {
                completeReplacement(frontend, oldBackend, currentServerName, nextServerName, nextBackend, nextCompressionAudit, result);
            }

            @Override
            public void backendLoginFailed(Channel nextBackend, String outcome) {
                metrics.backendReplacement(outcome);
                session.finishBackendReplacement();
                if (nextBackend != null && nextBackend.isOpen()) {
                    nextBackend.close();
                }
                resumeOldRelay(frontend, oldBackend);
                result.complete(failure(outcome, currentServerName, nextServerName));
            }
        };
        try {
            backendConnector.connectForSwitchLogin(frontend, selected, nextCompressionAudit, session, listener).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                metrics.backendConnectFailure();
                metrics.backendReplacement("connect_failure");
                session.finishBackendReplacement();
                resumeOldRelay(frontend, oldBackend);
                result.complete(failure("connect_failure", currentServerName, nextServerName));
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
                resumeOldRelay(frontend, oldBackend);
                result.complete(failure("backend_login_write_failure", currentServerName, nextServerName));
            }
            });
        } catch (RuntimeException exception) {
            metrics.backendConnectFailure();
            metrics.backendReplacement("connect_failure");
            session.finishBackendReplacement();
            resumeOldRelay(frontend, oldBackend);
            result.complete(failure("connect_failure", currentServerName, nextServerName));
        }
    }

    private void completeReplacement(
            Channel frontend,
            Channel oldBackend,
            String currentServerName,
            String nextServerName,
            Channel nextBackend,
            MinecraftCompressionAuditState nextCompressionAudit,
            CompletableFuture<PlayerTransferResult> result) {
        if (result.isDone()) {
            return;
        }
        try {
            var identity = session.identity();
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
                    this);
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
                    customPayloadPolicy,
                    identity.playerName().isBlank() ? null : identity.playerName(),
                    identity,
                    compressionRewriteEnabled,
                    compressionRewriteMaxEventLoopDelayMillis,
                    this,
                    commands,
                    events,
                    protocolVersion);
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

            if (oldBackend != null && oldBackend.isOpen()) {
                oldBackend.attr(MIGRATING_BACKEND).set(true);
                oldBackend.close();
            }
            session.finishBackendReplacement();
            frontend.config().setAutoRead(false);
            nextBackend.config().setAutoRead(false);
            frontend.read();
            nextBackend.read();
            metrics.backendReplacement("success");
            metrics.routedConnection();
            metrics.serverConnectionOpened(nextServerName);
            if (!identity.playerName().isBlank()) {
                metrics.playerSessionStarted(identity.playerName(), nextServerName, identity.remoteAddress());
            }
            result.complete(PlayerTransferResult.success(identity.playerName(), currentServerName, nextServerName));
        } catch (RuntimeException exception) {
            metrics.backendReplacement("pipeline_failure");
            session.finishBackendReplacement();
            if (nextBackend.isOpen()) {
                nextBackend.close();
            }
            resumeOldRelay(frontend, oldBackend);
            result.complete(failure("pipeline_failure", currentServerName, nextServerName));
        }
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

    void closeServerConnection(String serverName) {
        metrics.serverConnectionClosed(serverName);
    }

    void closePlayerSession(String playerName) {
        sessions.unregister(session);
        metrics.playerSessionClosed(playerName);
    }

    private PlayerTransferResult failure(String outcome, String sourceServer, String targetServer) {
        return PlayerTransferResult.failure(outcome, session.identity().playerName(), sourceServer, targetServer);
    }

    private dev.strataproxy.api.server.RegisteredServer target(String targetServerName) {
        try {
            return targetResolver.resolveTarget(targetServerName.trim()).orElse(null);
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
}

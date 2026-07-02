package dev.strataproxy.network;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class RelaySession {
    private final RelaySessionIdentity identity;
    private final AtomicBoolean backendReplacementInProgress = new AtomicBoolean();
    private final AtomicReference<FrontendRelayHandler> frontendRelay = new AtomicReference<>();
    private final AtomicReference<io.netty.channel.Channel> frontend = new AtomicReference<>();
    private final AtomicReference<io.netty.channel.Channel> backend = new AtomicReference<>();
    private final AtomicReference<String> serverName = new AtomicReference<>("");
    private final AtomicReference<BackendReplacementController> replacementController = new AtomicReference<>();
    private final AtomicReference<RelayLoginSession> loginSession = new AtomicReference<>();
    private final AtomicReference<MinecraftForgeHandshakeTracker> forgeHandshakeTracker = new AtomicReference<>();
    private final AtomicReference<PendingBackendTransfer> pendingBackendTransfer = new AtomicReference<>();
    private final AtomicBoolean legacyForgeClientDetected = new AtomicBoolean();
    private final MinecraftLegacyClientStateTracker clientStateTracker;
    private final MinecraftPluginChannelRegistry pluginChannelRegistry;

    RelaySession(String remoteAddress) {
        this(new RelaySessionIdentity(remoteAddress));
    }

    RelaySession(RelaySessionIdentity identity) {
        this(identity, NetworkTuning.defaults().maxFrameBytes());
    }

    RelaySession(RelaySessionIdentity identity, int maxFrameBytes) {
        this.identity = identity == null ? new RelaySessionIdentity("") : identity;
        this.clientStateTracker = new MinecraftLegacyClientStateTracker(maxFrameBytes);
        this.pluginChannelRegistry = new MinecraftPluginChannelRegistry(maxFrameBytes);
    }

    RelaySessionIdentity identity() {
        return identity;
    }

    boolean beginBackendReplacement() {
        return backendReplacementInProgress.compareAndSet(false, true);
    }

    void finishBackendReplacement() {
        backendReplacementInProgress.set(false);
    }

    FrontendRelayHandler frontendRelay() {
        return frontendRelay.get();
    }

    void frontendRelay(FrontendRelayHandler handler) {
        frontendRelay.set(handler);
    }

    void attach(io.netty.channel.Channel frontend, io.netty.channel.Channel backend, String serverName) {
        this.frontend.set(frontend);
        this.backend.set(backend);
        this.serverName.set(serverName == null ? "" : serverName);
    }

    io.netty.channel.Channel frontend() {
        return frontend.get();
    }

    io.netty.channel.Channel backend() {
        return backend.get();
    }

    String serverName() {
        return serverName.get();
    }

    BackendReplacementController replacementController() {
        return replacementController.get();
    }

    void replacementController(BackendReplacementController replacementController) {
        this.replacementController.set(replacementController);
    }

    RelayLoginSession loginSession() {
        return loginSession.get();
    }

    void loginSession(RelayLoginSession loginSession) {
        this.loginSession.set(loginSession);
    }

    void legacyForgeClientDetected(boolean detected) {
        if (detected) {
            legacyForgeClientDetected.set(true);
        }
    }

    boolean legacyForgeClientDetected() {
        return legacyForgeClientDetected.get();
    }

    MinecraftForgeHandshakeTracker startForgeHandshakeTracker(int maxFrameBytes, MinecraftProtocolProfile profile) {
        var previous = forgeHandshakeTracker.get();
        var resetRequired = resetRequiredForNextForgeServer(previous, profile);
        var next = new MinecraftForgeHandshakeTracker(maxFrameBytes, profile, resetRequired);
        previous = forgeHandshakeTracker.getAndSet(next);
        if (previous != null) {
            previous.close();
        }
        return next;
    }

    ForgeHandshakeTrackerSwap beginForgeHandshakeTrackerSwap(int maxFrameBytes, MinecraftProtocolProfile profile) {
        var previous = forgeHandshakeTracker.get();
        var next = new MinecraftForgeHandshakeTracker(maxFrameBytes, profile, resetRequiredForNextForgeServer(previous, profile));
        previous = forgeHandshakeTracker.getAndSet(next);
        return new ForgeHandshakeTrackerSwap(previous, next);
    }

    void commitForgeHandshakeTrackerSwap(ForgeHandshakeTrackerSwap swap) {
        if (swap == null) {
            return;
        }
        if (swap.previous() != null) {
            swap.previous().close();
        }
    }

    void rollbackForgeHandshakeTrackerSwap(ForgeHandshakeTrackerSwap swap) {
        if (swap == null) {
            return;
        }
        if (forgeHandshakeTracker.compareAndSet(swap.next(), swap.previous())) {
            swap.next().close();
        } else if (forgeHandshakeTracker.get() != swap.next()) {
            swap.next().close();
        }
    }

    private boolean resetRequiredForNextForgeServer(
            MinecraftForgeHandshakeTracker previous,
            MinecraftProtocolProfile profile) {
        return legacyForgeClientDetected.get()
                && profile != null
                && profile.legacyForgeHandshakeSupported()
                && !serverName.get().isBlank()
                && (previous == null || !previous.complete());
    }

    MinecraftForgeHandshakeTracker forgeHandshakeTracker() {
        return forgeHandshakeTracker.get();
    }

    MinecraftLegacyClientStateTracker clientStateTracker() {
        return clientStateTracker;
    }

    void closeClientStateTracker() {
        clientStateTracker.close();
    }

    MinecraftPluginChannelRegistry pluginChannelRegistry() {
        return pluginChannelRegistry;
    }

    void closePluginChannelRegistry() {
        pluginChannelRegistry.close();
    }

    PendingBackendTransfer deferBackendTransfer(String targetServerName, java.util.concurrent.CompletableFuture<PlayerTransferResult> result) {
        if (targetServerName == null || targetServerName.isBlank() || result == null) {
            return null;
        }
        var pending = new PendingBackendTransfer(targetServerName, result);
        return pendingBackendTransfer.compareAndSet(null, pending) ? pending : null;
    }

    PendingBackendTransfer consumePendingBackendTransfer() {
        return pendingBackendTransfer.getAndSet(null);
    }

    boolean consumePendingBackendTransfer(PendingBackendTransfer pending) {
        return pending != null && pendingBackendTransfer.compareAndSet(pending, null);
    }

    PendingBackendTransfer pendingBackendTransfer() {
        return pendingBackendTransfer.get();
    }

    PlayerTransferResult failPendingBackendTransfer(String outcome) {
        var pending = consumePendingBackendTransfer();
        if (pending != null) {
            var result = PlayerTransferResult.failure(
                    outcome,
                    identity.playerName(),
                    serverName(),
                    pending.targetServerName());
            pending.result.complete(result);
            return result;
        }
        return null;
    }

    record PendingBackendTransfer(String targetServerName, java.util.concurrent.CompletableFuture<PlayerTransferResult> result) {
    }

    record ForgeHandshakeTrackerSwap(MinecraftForgeHandshakeTracker previous, MinecraftForgeHandshakeTracker next) {
    }
}

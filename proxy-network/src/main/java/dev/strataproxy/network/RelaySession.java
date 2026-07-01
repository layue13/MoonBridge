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

    RelaySession(String remoteAddress) {
        this(new RelaySessionIdentity(remoteAddress));
    }

    RelaySession(RelaySessionIdentity identity) {
        this.identity = identity == null ? new RelaySessionIdentity("") : identity;
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
}

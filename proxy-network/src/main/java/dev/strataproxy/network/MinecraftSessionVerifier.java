package dev.strataproxy.network;

import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

interface MinecraftSessionVerifier {
    CompletionStage<SessionVerificationResult> verify(String username, String serverHash, SocketAddress remoteAddress);

    static MinecraftSessionVerifier disabled() {
        return (username, serverHash, remoteAddress) -> CompletableFuture.completedFuture(SessionVerificationResult.allowed("disabled"));
    }

    record SessionVerificationResult(boolean allowed, String reason) {
        static SessionVerificationResult allowed(String reason) {
            return new SessionVerificationResult(true, reason == null || reason.isBlank() ? "allowed" : reason);
        }

        static SessionVerificationResult denied(String reason) {
            return new SessionVerificationResult(false, reason == null || reason.isBlank() ? "denied" : reason);
        }
    }
}

package dev.strataproxy.network;

import java.net.SocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

interface MinecraftSessionVerifier {
    CompletionStage<SessionVerificationResult> verify(String username, String serverHash, SocketAddress remoteAddress);

    static MinecraftSessionVerifier disabled() {
        return (username, serverHash, remoteAddress) -> CompletableFuture.completedFuture(SessionVerificationResult.allowed(
                "disabled",
                new GameProfile(null, username, List.of())));
    }

    record SessionVerificationResult(boolean allowed, String reason, GameProfile profile) {
        static SessionVerificationResult allowed(String reason) {
            return allowed(reason, null);
        }

        static SessionVerificationResult allowed(String reason, GameProfile profile) {
            return new SessionVerificationResult(true, reason == null || reason.isBlank() ? "allowed" : reason, profile);
        }

        static SessionVerificationResult denied(String reason) {
            return new SessionVerificationResult(false, reason == null || reason.isBlank() ? "denied" : reason, null);
        }
    }

    record GameProfile(UUID id, String name, List<Property> properties) {
        public GameProfile {
            name = name == null ? "" : name;
            properties = properties == null ? List.of() : List.copyOf(properties);
        }
    }

    record Property(String name, String value, String signature) {
        public Property {
            name = name == null ? "" : name;
            value = value == null ? "" : value;
            signature = signature == null ? "" : signature;
        }
    }
}

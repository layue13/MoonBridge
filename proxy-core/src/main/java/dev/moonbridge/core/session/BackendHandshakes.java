package dev.moonbridge.core.session;

import dev.moonbridge.api.PlayerIdentity;
import dev.moonbridge.core.auth.VerifiedProfile;
import dev.moonbridge.core.forwarding.BungeeLegacyForwarding;
import dev.moonbridge.core.protocol.MinecraftHandshake;
import dev.moonbridge.core.protocol.ProtocolProfile;
import dev.moonbridge.messaging.session.ForwardedSessionProof;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Builds the handshake a backend receives: forwarded identity plus, when bound, a session proof. */
final class BackendHandshakes {
    private BackendHandshakes() { }

    static ByteBuf encode(ProxySessionListener owner, Channel frontend, MinecraftHandshake handshake,
                          PlayerIdentity identity, String username, VerifiedProfile verifiedProfile,
                          String backendName, long backendEpoch) {
        byte[] secret = owner.sessionBindingSecret(backendName);
        try {
            if (owner.onlineMode()) {
                return forward(owner, frontend, handshake, identity, verifiedProfile, secret, backendName, backendEpoch);
            }
            if (secret == null) return handshake.encode(frontend.alloc(), ProtocolProfile.minecraft1710());
            // OFFLINE identity is derived by this proxy and is not a Mojang-authenticated profile.
            // Only forward it when this backend has explicitly configured session binding.
            VerifiedProfile offlineProfile = new VerifiedProfile(identity.playerId(), username, List.of());
            return forward(owner, frontend, handshake, identity, offlineProfile, secret, backendName, backendEpoch);
        } finally {
            if (secret != null) Arrays.fill(secret, (byte) 0);
        }
    }

    private static ByteBuf forward(ProxySessionListener owner, Channel frontend, MinecraftHandshake handshake,
                                   PlayerIdentity identity, VerifiedProfile profile, byte[] secret,
                                   String backendName, long backendEpoch) {
        String proof = null;
        if (secret != null && backendEpoch > 0) {
            long expiresAt = Math.addExact(System.currentTimeMillis(), ForwardedSessionProof.MAX_LIFETIME_MILLIS);
            proof = ForwardedSessionProof.create(owner.proxyEpoch(), identity.playerId(), identity.connectionId(),
                    backendName, backendEpoch, UUID.randomUUID(), expiresAt, secret);
        }
        return BungeeLegacyForwarding.encode(frontend.alloc(), handshake,
                (InetSocketAddress) frontend.remoteAddress(), profile, proof);
    }
}

package dev.moonbridge.messaging.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ForwardedSessionProofTest {
    private static final byte[] SECRET = new byte[32];
    private static final UUID PROXY_EPOCH = UUID.randomUUID();
    private static final UUID PLAYER_ID = UUID.randomUUID();
    private static final UUID NONCE = UUID.randomUUID();

    @Test
    void proofAuthenticatesFullSessionAndRoute() {
        String token = ForwardedSessionProof.create(PROXY_EPOCH, PLAYER_ID, 42, "island-a", 99, NONCE,
                50_000, SECRET);
        ForwardedSessionProof.Claims claims = ForwardedSessionProof.verify(
                token, SECRET, "island-a", PROXY_EPOCH, 99, PLAYER_ID, 49_999).get();
        assertEquals(PROXY_EPOCH, claims.proxyEpoch());
        assertEquals(42, claims.connectionId());
        assertEquals(99, claims.backendEpoch());
        assertEquals(NONCE, claims.nonce());
        assertFalse(ForwardedSessionProof.verify(token, SECRET, "island-b", PROXY_EPOCH, 99, PLAYER_ID, 49_999).isPresent());
        assertFalse(ForwardedSessionProof.verify(token, SECRET, "island-a", PROXY_EPOCH, 99, UUID.randomUUID(), 49_999).isPresent());
        assertFalse(ForwardedSessionProof.verify(token, SECRET, "island-a", UUID.randomUUID(), 99, PLAYER_ID, 49_999).isPresent());
        assertFalse(ForwardedSessionProof.verify(token, SECRET, "island-a", PROXY_EPOCH, 100, PLAYER_ID, 49_999).isPresent());
    }

    @Test
    void malformedTamperedOrExpiredProofFailsClosed() {
        String token = ForwardedSessionProof.create(PROXY_EPOCH, PLAYER_ID, 42, "island-a", 99, NONCE,
                50_000, SECRET);
        assertFalse(ForwardedSessionProof.verify(token, SECRET, "island-a", PROXY_EPOCH, 99, PLAYER_ID, 50_000).isPresent());
        assertFalse(ForwardedSessionProof.verify(token + "x", SECRET, "island-a", PROXY_EPOCH, 99, PLAYER_ID, 49_999).isPresent());
        assertFalse(ForwardedSessionProof.verify("not-a-proof", SECRET, "island-a", PROXY_EPOCH, 99, PLAYER_ID, 49_999).isPresent());
        byte[] wrongSecret = new byte[32];
        wrongSecret[0] = 1;
        assertFalse(ForwardedSessionProof.verify(token, wrongSecret, "island-a", PROXY_EPOCH, 99, PLAYER_ID, 49_999).isPresent());
    }
}

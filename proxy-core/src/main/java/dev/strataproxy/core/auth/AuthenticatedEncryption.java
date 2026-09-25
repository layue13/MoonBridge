package dev.strataproxy.core.auth;

import java.util.Arrays;

/** Decrypted key material accepted only after the RSA verify token matched. */
public final class AuthenticatedEncryption {
    public static final int SHARED_SECRET_BYTES = 16;
    private final byte[] sharedSecret;

    AuthenticatedEncryption(byte[] sharedSecret) {
        if (sharedSecret == null || sharedSecret.length != SHARED_SECRET_BYTES) {
            throw new IllegalArgumentException("AES shared secret must contain exactly 16 bytes");
        }
        this.sharedSecret = Arrays.copyOf(sharedSecret, sharedSecret.length);
    }

    public byte[] sharedSecret() { return sharedSecret.clone(); }
}

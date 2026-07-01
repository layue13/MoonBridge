package dev.strataproxy.network;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.time.Duration;

/**
 * Runtime state for Minecraft offline-mode or online-mode login authentication.
 */
public final class MinecraftAuthRuntime {
    private final boolean onlineMode;
    private final KeyPair keyPair;
    private final int verifyTokenBytes;
    private final SecureRandom random;
    private final MinecraftSessionVerifier sessionVerifier;

    private MinecraftAuthRuntime(
            boolean onlineMode,
            KeyPair keyPair,
            int verifyTokenBytes,
            SecureRandom random,
            MinecraftSessionVerifier sessionVerifier) {
        if (onlineMode && keyPair == null) {
            throw new IllegalArgumentException("keyPair must not be null when onlineMode is enabled");
        }
        if (verifyTokenBytes <= 0) {
            throw new IllegalArgumentException("verifyTokenBytes must be positive");
        }
        this.onlineMode = onlineMode;
        this.keyPair = keyPair;
        this.verifyTokenBytes = verifyTokenBytes;
        this.random = random == null ? new SecureRandom() : random;
        this.sessionVerifier = sessionVerifier == null ? MinecraftSessionVerifier.disabled() : sessionVerifier;
    }

    /**
     * @return runtime that accepts offline-mode logins without encryption
     */
    public static MinecraftAuthRuntime offline() {
        return new MinecraftAuthRuntime(false, null, 4, new SecureRandom(), MinecraftSessionVerifier.disabled());
    }

    /**
     * Creates an online-mode authentication runtime.
     *
     * @param rsaKeyBits RSA key size used in the login encryption request
     * @param verifyTokenBytes verify-token size
     * @param sessionVerification whether Mojang session verification is enabled
     * @param sessionVerificationTimeout timeout for Mojang session verification
     * @return online-mode authentication runtime
     */
    public static MinecraftAuthRuntime online(int rsaKeyBits, int verifyTokenBytes, boolean sessionVerification, Duration sessionVerificationTimeout) {
        if (rsaKeyBits < 1024) {
            throw new IllegalArgumentException("rsaKeyBits must be at least 1024");
        }
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(rsaKeyBits);
            return new MinecraftAuthRuntime(
                    true,
                    generator.generateKeyPair(),
                    verifyTokenBytes,
                    new SecureRandom(),
                    sessionVerification
                            ? new MojangMinecraftSessionVerifier(sessionVerificationTimeout)
                            : MinecraftSessionVerifier.disabled());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("failed to initialize Minecraft online-mode RSA keypair", exception);
        }
    }

    static MinecraftAuthRuntime onlineForTesting(KeyPair keyPair, int verifyTokenBytes, SecureRandom random) {
        return new MinecraftAuthRuntime(true, keyPair, verifyTokenBytes, random, MinecraftSessionVerifier.disabled());
    }

    static MinecraftAuthRuntime onlineForTesting(
            KeyPair keyPair,
            int verifyTokenBytes,
            SecureRandom random,
            MinecraftSessionVerifier sessionVerifier) {
        return new MinecraftAuthRuntime(true, keyPair, verifyTokenBytes, random, sessionVerifier);
    }

    boolean onlineMode() {
        return onlineMode;
    }

    KeyPair keyPair() {
        return keyPair;
    }

    byte[] newVerifyToken() {
        var token = new byte[verifyTokenBytes];
        random.nextBytes(token);
        return token;
    }

    MinecraftSessionVerifier sessionVerifier() {
        return sessionVerifier;
    }
}

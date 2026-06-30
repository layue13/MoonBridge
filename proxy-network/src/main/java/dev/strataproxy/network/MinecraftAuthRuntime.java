package dev.strataproxy.network;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;

public final class MinecraftAuthRuntime {
    private final boolean onlineMode;
    private final KeyPair keyPair;
    private final int verifyTokenBytes;
    private final SecureRandom random;

    private MinecraftAuthRuntime(boolean onlineMode, KeyPair keyPair, int verifyTokenBytes, SecureRandom random) {
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
    }

    public static MinecraftAuthRuntime offline() {
        return new MinecraftAuthRuntime(false, null, 4, new SecureRandom());
    }

    public static MinecraftAuthRuntime online(int rsaKeyBits, int verifyTokenBytes) {
        if (rsaKeyBits < 1024) {
            throw new IllegalArgumentException("rsaKeyBits must be at least 1024");
        }
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(rsaKeyBits);
            return new MinecraftAuthRuntime(true, generator.generateKeyPair(), verifyTokenBytes, new SecureRandom());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("failed to initialize Minecraft online-mode RSA keypair", exception);
        }
    }

    static MinecraftAuthRuntime onlineForTesting(KeyPair keyPair, int verifyTokenBytes, SecureRandom random) {
        return new MinecraftAuthRuntime(true, keyPair, verifyTokenBytes, random);
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
}

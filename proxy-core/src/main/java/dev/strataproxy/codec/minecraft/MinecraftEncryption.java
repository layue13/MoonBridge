package dev.strataproxy.codec.minecraft;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * Helpers for Minecraft login encryption and session server hash calculation.
 */
public final class MinecraftEncryption {
    /** Minecraft shared secrets are fixed at 16 bytes for AES/CFB8. */
    public static final int SHARED_SECRET_BYTES = 16;
    private static final String AES_CFB8 = "AES/CFB8/NoPadding";
    private static final String RSA_PKCS1 = "RSA/ECB/PKCS1Padding";
    private static final String SHA1 = "SHA-1";

    private MinecraftEncryption() {
    }

    /**
     * Creates an AES/CFB8 cipher for outbound encrypted traffic.
     *
     * @param sharedSecret 16-byte Minecraft shared secret
     * @return initialized encrypt cipher
     */
    public static Cipher newEncryptCipher(byte[] sharedSecret) {
        return newCipher(Cipher.ENCRYPT_MODE, sharedSecret);
    }

    /**
     * Creates an AES/CFB8 cipher for inbound encrypted traffic.
     *
     * @param sharedSecret 16-byte Minecraft shared secret
     * @return initialized decrypt cipher
     */
    public static Cipher newDecryptCipher(byte[] sharedSecret) {
        return newCipher(Cipher.DECRYPT_MODE, sharedSecret);
    }

    /**
     * Decrypts and validates the login shared secret.
     *
     * @param privateKey server private key
     * @param encryptedSharedSecret encrypted shared-secret payload from the client
     * @return decrypted 16-byte shared secret
     */
    public static byte[] decryptSharedSecret(PrivateKey privateKey, byte[] encryptedSharedSecret) {
        if (encryptedSharedSecret == null || encryptedSharedSecret.length == 0) {
            throw new IllegalArgumentException("encryptedSharedSecret must not be empty");
        }
        var sharedSecret = decryptRsa(privateKey, encryptedSharedSecret);
        validateSharedSecret(sharedSecret);
        return sharedSecret;
    }

    /**
     * Decrypts the login verify token.
     *
     * @param privateKey server private key
     * @param encryptedVerifyToken encrypted verify-token payload from the client
     * @return decrypted verify token
     */
    public static byte[] decryptVerifyToken(PrivateKey privateKey, byte[] encryptedVerifyToken) {
        if (encryptedVerifyToken == null || encryptedVerifyToken.length == 0) {
            throw new IllegalArgumentException("encryptedVerifyToken must not be empty");
        }
        return decryptRsa(privateKey, encryptedVerifyToken);
    }

    /**
     * Computes the signed SHA-1 server hash used by Mojang session verification.
     *
     * @param serverId server id string from the login flow
     * @param sharedSecret negotiated shared secret
     * @param publicKey server public key
     * @return Minecraft-compatible signed hexadecimal hash
     */
    public static String serverHash(String serverId, byte[] sharedSecret, PublicKey publicKey) {
        if (serverId == null) {
            throw new IllegalArgumentException("serverId must not be null");
        }
        validateSharedSecret(sharedSecret);
        if (publicKey == null) {
            throw new IllegalArgumentException("publicKey must not be null");
        }
        try {
            var digest = MessageDigest.getInstance(SHA1);
            digest.update(serverId.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            digest.update(sharedSecret);
            digest.update(publicKey.getEncoded());
            return new BigInteger(digest.digest()).toString(16);
        } catch (GeneralSecurityException exception) {
            throw new MinecraftCodecException("failed to compute Minecraft server hash", exception);
        }
    }

    private static Cipher newCipher(int mode, byte[] sharedSecret) {
        validateSharedSecret(sharedSecret);
        try {
            var key = new SecretKeySpec(sharedSecret, "AES");
            var cipher = Cipher.getInstance(AES_CFB8);
            cipher.init(mode, key, new javax.crypto.spec.IvParameterSpec(sharedSecret));
            return cipher;
        } catch (GeneralSecurityException exception) {
            throw new MinecraftCodecException("failed to initialize Minecraft AES cipher", exception);
        }
    }

    private static byte[] decryptRsa(PrivateKey privateKey, byte[] encryptedPayload) {
        if (privateKey == null) {
            throw new IllegalArgumentException("privateKey must not be null");
        }
        try {
            var cipher = Cipher.getInstance(RSA_PKCS1);
            cipher.init(Cipher.DECRYPT_MODE, privateKey);
            return cipher.doFinal(encryptedPayload);
        } catch (GeneralSecurityException exception) {
            throw new MinecraftCodecException("failed to decrypt Minecraft RSA payload", exception);
        }
    }

    private static void validateSharedSecret(byte[] sharedSecret) {
        if (sharedSecret == null || sharedSecret.length != SHARED_SECRET_BYTES) {
            throw new IllegalArgumentException("sharedSecret must be exactly 16 bytes");
        }
    }
}

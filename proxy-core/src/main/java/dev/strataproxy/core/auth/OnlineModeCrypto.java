package dev.strataproxy.core.auth;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.util.Arrays;
import javax.crypto.Cipher;

/** Cryptographic operations used by the legacy online-mode login exchange. */
public final class OnlineModeCrypto {
    private OnlineModeCrypto() { }

    public static AuthenticatedEncryption decrypt(PrivateKey privateKey, MinecraftEncryptionRequest request,
                                                   MinecraftEncryptionResponse response) throws GeneralSecurityException {
        if (privateKey == null || request == null || response == null) throw new IllegalArgumentException("key and packets are required");
        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.DECRYPT_MODE, privateKey);
        byte[] secret = null;
        byte[] token = null;
        try {
            secret = rsa.doFinal(response.encryptedSharedSecret());
            token = rsa.doFinal(response.encryptedVerifyToken());
            if (secret.length != AuthenticatedEncryption.SHARED_SECRET_BYTES) {
                throw new GeneralSecurityException("decrypted shared secret must contain exactly 16 bytes");
            }
            if (!MessageDigest.isEqual(request.verifyToken(), token)) {
                throw new GeneralSecurityException("encryption verify token mismatch");
            }
            return new AuthenticatedEncryption(secret);
        } finally {
            if (secret != null) Arrays.fill(secret, (byte) 0);
            if (token != null) Arrays.fill(token, (byte) 0);
        }
    }

    /** Computes the signed, variable-width hexadecimal hash expected by hasJoined. */
    public static String serverHash(String serverId, byte[] sharedSecret, byte[] encodedPublicKey) {
        if (serverId == null || serverId.length() > 20 || sharedSecret == null || encodedPublicKey == null) {
            throw new IllegalArgumentException("serverId must be at most 20 characters and hash inputs are required");
        }
        for (int i = 0; i < serverId.length(); i++) if (serverId.charAt(i) > 0x7f) throw new IllegalArgumentException("serverId must be ASCII");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(serverId.getBytes(StandardCharsets.US_ASCII));
            digest.update(sharedSecret);
            digest.update(encodedPublicKey);
            return new BigInteger(digest.digest()).toString(16);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("SHA-1 is required by the Java platform", impossible);
        }
    }
}

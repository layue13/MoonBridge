package dev.strataproxy.core.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import javax.crypto.Cipher;
import org.junit.jupiter.api.Test;

class OnlineModeCryptoTest {
    @Test
    void decryptsSecretAndAcceptsOnlyMatchingVerifyToken() throws Exception {
        KeyPair pair = rsaKeyPair();
        byte[] token = {1, 2, 3, 4};
        byte[] secret = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
        MinecraftEncryptionRequest request = new MinecraftEncryptionRequest("", pair.getPublic().getEncoded(), token);
        MinecraftEncryptionResponse response = new MinecraftEncryptionResponse(
                rsaEncrypt(pair.getPublic(), secret), rsaEncrypt(pair.getPublic(), token));
        AuthenticatedEncryption result = OnlineModeCrypto.decrypt(pair.getPrivate(), request, response);
        assertArrayEquals(secret, result.sharedSecret());

        MinecraftEncryptionResponse mismatch = new MinecraftEncryptionResponse(
                rsaEncrypt(pair.getPublic(), secret), rsaEncrypt(pair.getPublic(), new byte[] {4, 3, 2, 1}));
        assertThrows(GeneralSecurityException.class, () -> OnlineModeCrypto.decrypt(pair.getPrivate(), request, mismatch));
    }

    @Test
    void rejectsInvalidRsaCiphertext() throws Exception {
        KeyPair pair = rsaKeyPair();
        MinecraftEncryptionRequest request = new MinecraftEncryptionRequest("", pair.getPublic().getEncoded(), new byte[] {1, 2, 3, 4});
        MinecraftEncryptionResponse response = new MinecraftEncryptionResponse(new byte[128], new byte[] {1});
        assertThrows(GeneralSecurityException.class, () -> OnlineModeCrypto.decrypt(pair.getPrivate(), request, response));
    }

    @Test
    void serverHashUsesSignedBigIntegerHexAndRawConcatenatedBytes() throws Exception {
        assertEquals("4ed1f46bbe04bc756bcb17c0c7ce3e4632f06a48",
                OnlineModeCrypto.serverHash("Notch", new byte[0], new byte[0]));
        assertEquals("-7c9d5b0044c130109a5d7b5fb5c317c02b4e28c1",
                OnlineModeCrypto.serverHash("jeb_", new byte[0], new byte[0]));

        byte[] secret = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] key = "key".getBytes(StandardCharsets.UTF_8);
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update("server".getBytes(StandardCharsets.US_ASCII));
        sha1.update(secret);
        sha1.update(key);
        assertEquals(new java.math.BigInteger(sha1.digest()).toString(16), OnlineModeCrypto.serverHash("server", secret, key));
    }

    private static KeyPair rsaKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        return generator.generateKeyPair();
    }

    private static byte[] rsaEncrypt(PublicKey key, byte[] clear) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return cipher.doFinal(clear);
    }
}

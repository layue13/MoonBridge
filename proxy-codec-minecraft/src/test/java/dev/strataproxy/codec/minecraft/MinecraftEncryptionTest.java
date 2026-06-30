package dev.strataproxy.codec.minecraft;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftEncryptionTest {
    private static final byte[] SHARED_SECRET = HexFormat.of().parseHex("00112233445566778899aabbccddeeff");

    @Test
    void cipherHandlersRoundTripArbitraryByteStream() {
        var plaintext = new byte[4096];
        new SecureRandom(new byte[] {1, 2, 3, 4}).nextBytes(plaintext);
        var encoder = new EmbeddedChannel(new MinecraftCipherEncoder(SHARED_SECRET));
        var decoder = new EmbeddedChannel(new MinecraftCipherDecoder(SHARED_SECRET));
        try {
            assertTrue(encoder.writeOutbound(Unpooled.wrappedBuffer(plaintext)));
            var encrypted = (ByteBuf) encoder.readOutbound();
            assertNotEquals(HexFormat.of().formatHex(plaintext), HexFormat.of().formatHex(bytes(encrypted)));

            assertTrue(decoder.writeInbound(encrypted));
            var decoded = (ByteBuf) decoder.readInbound();
            try {
                assertArrayEquals(plaintext, bytes(decoded));
            } finally {
                decoded.release();
            }
        } finally {
            assertFalse(encoder.finishAndReleaseAll());
            assertFalse(decoder.finishAndReleaseAll());
        }
    }

    @Test
    void decoderPreservesCipherStateAcrossSplitInput() {
        var plaintext = "split encrypted minecraft stream".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var encrypt = MinecraftEncryption.newEncryptCipher(SHARED_SECRET);
        var encrypted = encrypt.update(plaintext);
        var decoder = new EmbeddedChannel(new MinecraftCipherDecoder(SHARED_SECRET));
        try {
            assertTrue(decoder.writeInbound(Unpooled.wrappedBuffer(encrypted, 0, 7)));
            assertTrue(decoder.writeInbound(Unpooled.wrappedBuffer(encrypted, 7, encrypted.length - 7)));
            var first = (ByteBuf) decoder.readInbound();
            var second = (ByteBuf) decoder.readInbound();
            try {
                var firstLength = first.readableBytes();
                var secondLength = second.readableBytes();
                var decoded = new byte[firstLength + secondLength];
                first.readBytes(decoded, 0, firstLength);
                second.readBytes(decoded, firstLength, secondLength);
                assertArrayEquals(plaintext, decoded);
            } finally {
                first.release();
                second.release();
            }
        } finally {
            assertFalse(decoder.finishAndReleaseAll());
        }
    }

    @Test
    void decryptsRsaWrappedSharedSecret() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        var keyPair = generator.generateKeyPair();
        var rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.ENCRYPT_MODE, keyPair.getPublic());

        var decrypted = MinecraftEncryption.decryptSharedSecret(keyPair.getPrivate(), rsa.doFinal(SHARED_SECRET));

        assertArrayEquals(SHARED_SECRET, decrypted);
    }

    @Test
    void computesSignedMinecraftServerHash() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        var keyPair = generator.generateKeyPair();

        var hash = MinecraftEncryption.serverHash("", SHARED_SECRET, keyPair.getPublic());

        assertFalse(hash.isBlank());
        assertEquals(hash, MinecraftEncryption.serverHash("", SHARED_SECRET, keyPair.getPublic()));
    }

    @Test
    void rejectsInvalidSharedSecretLength() {
        assertThrows(IllegalArgumentException.class, () -> MinecraftEncryption.newEncryptCipher(new byte[15]));
        assertThrows(IllegalArgumentException.class, () -> MinecraftEncryption.newDecryptCipher(new byte[17]));
    }

    private static byte[] bytes(ByteBuf buffer) {
        var bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }
}

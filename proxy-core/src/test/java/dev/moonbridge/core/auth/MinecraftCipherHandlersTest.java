package dev.moonbridge.core.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class MinecraftCipherHandlersTest {
    private final byte[] secret = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
    private final byte[] plaintext = new byte[513];

    MinecraftCipherHandlersTest() {
        for (int i = 0; i < plaintext.length; i++) plaintext[i] = (byte) (i * 31 + 17);
    }

    @Test
    void inboundDecryptsContinuousStreamIndependentOfTcpChunkBoundaries() throws Exception {
        byte[] ciphertext = transform(plaintext, Cipher.ENCRYPT_MODE);
        EmbeddedChannel oneShot = new EmbeddedChannel(new MinecraftCipherDecoder(secret.clone()));
        oneShot.writeInbound(Unpooled.wrappedBuffer(ciphertext.clone()));
        assertArrayEquals(plaintext, collectInbound(oneShot));
        oneShot.finishAndReleaseAll();

        EmbeddedChannel segmented = new EmbeddedChannel(new MinecraftCipherDecoder(secret.clone()));
        segmented.writeInbound(Unpooled.wrappedBuffer(Arrays.copyOfRange(ciphertext, 0, 1)));
        segmented.writeInbound(Unpooled.wrappedBuffer(Arrays.copyOfRange(ciphertext, 1, 17)));
        segmented.writeInbound(Unpooled.wrappedBuffer(Arrays.copyOfRange(ciphertext, 17, 257)));
        segmented.writeInbound(Unpooled.wrappedBuffer(Arrays.copyOfRange(ciphertext, 257, ciphertext.length)));
        assertArrayEquals(plaintext, collectInbound(segmented));
        segmented.finishAndReleaseAll();
    }

    @Test
    void outboundEncryptionMatchesJceForSingleAndSegmentedWrites() throws Exception {
        byte[] expected = transform(plaintext, Cipher.ENCRYPT_MODE);
        EmbeddedChannel oneShot = new EmbeddedChannel(new MinecraftCipherEncoder(secret.clone()));
        oneShot.writeOutbound(Unpooled.wrappedBuffer(plaintext.clone()));
        assertArrayEquals(expected, collectOutbound(oneShot));
        oneShot.finishAndReleaseAll();

        EmbeddedChannel segmented = new EmbeddedChannel(new MinecraftCipherEncoder(secret.clone()));
        segmented.writeOutbound(Unpooled.wrappedBuffer(Arrays.copyOfRange(plaintext, 0, 3)));
        segmented.writeOutbound(Unpooled.wrappedBuffer(Arrays.copyOfRange(plaintext, 3, 219)));
        segmented.writeOutbound(Unpooled.wrappedBuffer(Arrays.copyOfRange(plaintext, 219, plaintext.length)));
        assertArrayEquals(expected, collectOutbound(segmented));
        segmented.finishAndReleaseAll();
    }

    @Test
    void directBuffersPreserveTheContinuousCipherAcrossWrites() throws Exception {
        byte[] ciphertext = transform(plaintext, Cipher.ENCRYPT_MODE);
        var outbound = new EmbeddedChannel(new MinecraftCipherEncoder(secret.clone()));
        ByteBuf firstPlain = directSegment(plaintext, 0, 17);
        ByteBuf secondPlain = directSegment(plaintext, 17, plaintext.length);
        try {
            outbound.writeOutbound(firstPlain);
            outbound.writeOutbound(secondPlain);
            assertEquals(0, firstPlain.refCnt());
            assertEquals(0, secondPlain.refCnt());
            assertArrayEquals(ciphertext, collectOutbound(outbound));
        } finally {
            outbound.finishAndReleaseAll();
        }

        var inbound = new EmbeddedChannel(new MinecraftCipherDecoder(secret.clone()));
        ByteBuf firstEncrypted = directSegment(ciphertext, 0, 17);
        ByteBuf secondEncrypted = directSegment(ciphertext, 17, ciphertext.length);
        try {
            inbound.writeInbound(firstEncrypted);
            inbound.writeInbound(secondEncrypted);
            assertEquals(0, firstEncrypted.refCnt());
            assertEquals(0, secondEncrypted.refCnt());
            assertArrayEquals(plaintext, collectInbound(inbound));
        } finally {
            inbound.finishAndReleaseAll();
        }
    }

    @Test
    void handlesReadOnlyCompositeBuffersAndReleasesTheirComponents() throws Exception {
        byte[] ciphertext = transform(plaintext, Cipher.ENCRYPT_MODE);
        var inbound = new EmbeddedChannel(new MinecraftCipherDecoder(secret.clone()));
        CompositeByteBuf encryptedInput = composite(ciphertext);
        try {
            inbound.writeInbound(encryptedInput.asReadOnly());
            assertEquals(0, encryptedInput.refCnt());
            assertArrayEquals(plaintext, collectInbound(inbound));
        } finally {
            inbound.finishAndReleaseAll();
        }

        var outbound = new EmbeddedChannel(new MinecraftCipherEncoder(secret.clone()));
        CompositeByteBuf plainInput = composite(plaintext);
        try {
            outbound.writeOutbound(plainInput.asReadOnly());
            assertEquals(0, plainInput.refCnt());
            assertArrayEquals(ciphertext, collectOutbound(outbound));
        } finally {
            outbound.finishAndReleaseAll();
        }
    }

    @Test
    void handlesReadOnlyDirectBuffer() throws Exception {
        var outbound = new EmbeddedChannel(new MinecraftCipherEncoder(secret.clone()));
        ByteBuf plainInput = directSegment(plaintext, 0, plaintext.length);
        try {
            outbound.writeOutbound(plainInput.asReadOnly());
            assertEquals(0, plainInput.refCnt());
            assertArrayEquals(transform(plaintext, Cipher.ENCRYPT_MODE), collectOutbound(outbound));
        } finally {
            outbound.finishAndReleaseAll();
        }
    }

    private static CompositeByteBuf composite(byte[] bytes) {
        int split = 17;
        ByteBuf first = Unpooled.directBuffer(split).writeBytes(bytes, 0, split);
        ByteBuf second = Unpooled.directBuffer(bytes.length - split).writeBytes(bytes, split, bytes.length - split);
        return Unpooled.compositeBuffer(2).addComponents(true, first, second);
    }

    private static ByteBuf directSegment(byte[] bytes, int start, int end) {
        return Unpooled.directBuffer(end - start).writeBytes(bytes, start, end - start);
    }

    private byte[] transform(byte[] input, int mode) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CFB8/NoPadding");
        SecretKeySpec key = new SecretKeySpec(secret, "AES");
        cipher.init(mode, key, new IvParameterSpec(secret));
        return cipher.doFinal(input);
    }

    private static byte[] collectInbound(EmbeddedChannel channel) {
        ByteBuf first = channel.readInbound();
        byte[] bytes = new byte[0];
        while (first != null) {
            try {
                byte[] joined = new byte[bytes.length + first.readableBytes()];
                System.arraycopy(bytes, 0, joined, 0, bytes.length);
                first.readBytes(joined, bytes.length, first.readableBytes());
                bytes = joined;
            } finally {
                first.release();
            }
            first = channel.readInbound();
        }
        return bytes;
    }

    private static byte[] collectOutbound(EmbeddedChannel channel) {
        ByteBuf first = channel.readOutbound();
        byte[] bytes = new byte[0];
        while (first != null) {
            try {
                byte[] joined = new byte[bytes.length + first.readableBytes()];
                System.arraycopy(bytes, 0, joined, 0, bytes.length);
                first.readBytes(joined, bytes.length, first.readableBytes());
                bytes = joined;
            } finally {
                first.release();
            }
            first = channel.readOutbound();
        }
        return bytes;
    }
}

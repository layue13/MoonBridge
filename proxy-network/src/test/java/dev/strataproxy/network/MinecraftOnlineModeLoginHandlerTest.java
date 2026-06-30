package dev.strataproxy.network;

import dev.strataproxy.codec.minecraft.MinecraftCipherDecoder;
import dev.strataproxy.codec.minecraft.MinecraftCipherEncoder;
import dev.strataproxy.codec.minecraft.MinecraftVarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import java.security.KeyPairGenerator;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftOnlineModeLoginHandlerTest {
    private static final byte[] SHARED_SECRET = HexFormat.of().parseHex("00112233445566778899aabbccddeeff");

    @Test
    void completesEncryptionHandshakeAndInstallsCiphers() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        var keyPair = generator.generateKeyPair();
        var verifyToken = new byte[] {9, 8, 7, 6};
        var callbackUsername = new AtomicReference<String>();
        var callbackSharedSecret = new AtomicReference<byte[]>();
        var callbackLoginPacketId = new AtomicReference<Integer>();
        var channel = new EmbeddedChannel(new MinecraftOnlineModeLoginHandler(
                4096,
                keyPair,
                verifyToken,
                MinecraftSessionVerifier.disabled(),
                (context, loginStartFrame, sharedSecret, username) -> {
                    try {
                        var probe = MinecraftProtocolCodec.probeFrame(loginStartFrame, 4096);
                        loginStartFrame.skipBytes(probe.varIntBytes());
                        callbackLoginPacketId.set(MinecraftProtocolCodec.readVarInt(loginStartFrame));
                    } finally {
                        loginStartFrame.release();
                    }
                    callbackSharedSecret.set(sharedSecret);
                    callbackUsername.set(username);
                }));
        try {
            assertFalse(channel.writeInbound(loginStartFrame("PlayerOne")));
            var request = (ByteBuf) channel.readOutbound();
            try {
                var requestPayload = unwrapFrame(request);
                try {
                    assertEquals(0x01, MinecraftProtocolCodec.readVarInt(requestPayload));
                    assertEquals("", MinecraftProtocolCodec.readString(requestPayload, 20));
                    assertArrayEquals(keyPair.getPublic().getEncoded(), readByteArray(requestPayload));
                    assertArrayEquals(verifyToken, readByteArray(requestPayload));
                } finally {
                    requestPayload.release();
                }
            } finally {
                request.release();
            }

            assertFalse(channel.writeInbound(encryptionResponseFrame(keyPair.getPublic(), SHARED_SECRET, verifyToken)));

            assertEquals("PlayerOne", callbackUsername.get());
            assertArrayEquals(SHARED_SECRET, callbackSharedSecret.get());
            assertEquals(0x00, callbackLoginPacketId.get());
            assertNotNull(channel.pipeline().get(MinecraftCipherDecoder.class));
            assertNotNull(channel.pipeline().get(MinecraftCipherEncoder.class));
        } finally {
            assertFalse(channel.finishAndReleaseAll());
        }
    }

    @Test
    void closesConnectionOnVerifyTokenMismatch() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        var keyPair = generator.generateKeyPair();
        var channel = new EmbeddedChannel(new MinecraftOnlineModeLoginHandler(
                4096,
                keyPair,
                new byte[] {1, 2, 3, 4},
                MinecraftSessionVerifier.disabled(),
                (context, loginStartFrame, sharedSecret, username) -> loginStartFrame.release()));
        try {
            assertFalse(channel.writeInbound(loginStartFrame("PlayerOne")));
            ((ByteBuf) channel.readOutbound()).release();

            assertThrows(IllegalArgumentException.class,
                    () -> channel.writeInbound(encryptionResponseFrame(keyPair.getPublic(), SHARED_SECRET, new byte[] {4, 3, 2, 1})));

            assertFalse(channel.isOpen());
        } finally {
            assertFalse(channel.finishAndReleaseAll());
        }
    }

    @Test
    void closesConnectionWhenSessionVerifierDeniesLogin() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        var keyPair = generator.generateKeyPair();
        var verifyToken = new byte[] {1, 2, 3, 4};
        var channel = new EmbeddedChannel(new MinecraftOnlineModeLoginHandler(
                4096,
                keyPair,
                verifyToken,
                (username, serverHash, remoteAddress) -> java.util.concurrent.CompletableFuture.completedFuture(
                        MinecraftSessionVerifier.SessionVerificationResult.denied("test-denied")),
                (context, loginStartFrame, sharedSecret, username) -> loginStartFrame.release()));
        try {
            assertFalse(channel.writeInbound(loginStartFrame("PlayerOne")));
            ((ByteBuf) channel.readOutbound()).release();

            assertThrows(IllegalArgumentException.class,
                    () -> channel.writeInbound(encryptionResponseFrame(keyPair.getPublic(), SHARED_SECRET, verifyToken)));

            assertFalse(channel.isOpen());
        } finally {
            assertFalse(channel.finishAndReleaseAll());
        }
    }

    private static ByteBuf loginStartFrame(String username) {
        var payload = Unpooled.buffer();
        MinecraftVarInts.write(payload, 0x00);
        writeString(payload, username);
        return frame(payload);
    }

    private static ByteBuf encryptionResponseFrame(java.security.PublicKey publicKey, byte[] sharedSecret, byte[] verifyToken) throws Exception {
        var rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.ENCRYPT_MODE, publicKey);
        var payload = Unpooled.buffer();
        MinecraftVarInts.write(payload, 0x01);
        writeByteArray(payload, rsa.doFinal(sharedSecret));
        writeByteArray(payload, rsa.doFinal(verifyToken));
        return frame(payload);
    }

    private static ByteBuf frame(ByteBuf payload) {
        var frame = Unpooled.buffer(MinecraftVarInts.encodedSize(payload.readableBytes()) + payload.readableBytes());
        MinecraftVarInts.write(frame, payload.readableBytes());
        frame.writeBytes(payload);
        payload.release();
        return frame;
    }

    private static ByteBuf unwrapFrame(ByteBuf frame) {
        var duplicate = frame.retainedDuplicate();
        try {
            var length = MinecraftVarInts.read(duplicate);
            assertTrue(duplicate.readableBytes() >= length);
            return duplicate.readRetainedSlice(length);
        } finally {
            duplicate.release();
        }
    }

    private static void writeString(ByteBuf output, String value) {
        var bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MinecraftVarInts.write(output, bytes.length);
        output.writeBytes(bytes);
    }

    private static void writeByteArray(ByteBuf output, byte[] value) {
        MinecraftVarInts.write(output, value.length);
        output.writeBytes(value);
    }

    private static byte[] readByteArray(ByteBuf input) {
        var length = MinecraftVarInts.read(input);
        var bytes = new byte[length];
        input.readBytes(bytes);
        return bytes;
    }
}

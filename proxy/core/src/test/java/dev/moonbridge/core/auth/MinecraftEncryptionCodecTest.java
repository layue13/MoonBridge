package dev.moonbridge.core.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.moonbridge.core.protocol.ProtocolException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class MinecraftEncryptionCodecTest {
    @Test
    void requestAndResponseRoundTripWithoutConsumingInput() {
        ByteBufAllocator allocator = ByteBufAllocator.DEFAULT;
        MinecraftEncryptionRequest request = new MinecraftEncryptionRequest("", new byte[] {1, 2, 3}, new byte[] {4, 5, 6, 7});
        ByteBuf requestBytes = request.encode(allocator);
        try {
            int readerIndex = requestBytes.readerIndex();
            MinecraftEncryptionRequest decoded = MinecraftEncryptionRequest.decode(requestBytes);
            assertEquals("", decoded.serverId());
            assertArrayEquals(request.publicKey(), decoded.publicKey());
            assertArrayEquals(request.verifyToken(), decoded.verifyToken());
            assertEquals(readerIndex, requestBytes.readerIndex());
        } finally {
            requestBytes.release();
        }

        MinecraftEncryptionResponse response = new MinecraftEncryptionResponse(new byte[] {8, 9}, new byte[] {10, 11});
        ByteBuf responseBytes = response.encode(allocator);
        try {
            MinecraftEncryptionResponse decoded = MinecraftEncryptionResponse.decode(responseBytes);
            assertArrayEquals(response.encryptedSharedSecret(), decoded.encryptedSharedSecret());
            assertArrayEquals(response.encryptedVerifyToken(), decoded.encryptedVerifyToken());
        } finally {
            responseBytes.release();
        }
    }

    @Test
    void rejectsBadPacketIdsLengthsTruncationAndTrailingBytes() {
        assertThrows(ProtocolException.class, () -> MinecraftEncryptionRequest.decode(Unpooled.wrappedBuffer(new byte[] {0})));
        assertThrows(ProtocolException.class, () -> MinecraftEncryptionRequest.decode(Unpooled.wrappedBuffer(new byte[] {1, 21})));
        assertThrows(ProtocolException.class, () -> MinecraftEncryptionRequest.decode(Unpooled.wrappedBuffer(new byte[] {1, 0, 0, 1, 1})));
        assertThrows(ProtocolException.class, () -> MinecraftEncryptionResponse.decode(Unpooled.wrappedBuffer(
                new byte[] {1, 0, 1, 9, 0, 1, 10, 0})));
        assertThrows(ProtocolException.class, () -> MinecraftEncryptionResponse.decode(Unpooled.wrappedBuffer(
                new byte[] {1, 0, 0, 0, 1, 10})));
    }
}

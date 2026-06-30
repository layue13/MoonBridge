package dev.strataproxy.network;

import dev.strataproxy.codec.minecraft.MinecraftVarInts;
import dev.strataproxy.observability.ProxyMetrics;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class VelocityModernForwardingTest {
    @Test
    void detectsVelocityLoginPluginRequest() {
        var request = VelocityModernForwarding.request(pluginRequest(7), 4096);

        assertTrue(request.matched());
        assertEquals(7, request.messageId());
    }

    @Test
    void writesVelocityLoginPluginResponsePayload() {
        var identity = new RelaySessionIdentity("/203.0.113.10:50000");
        identity.profile(new MinecraftSessionVerifier.GameProfile(
                UUID.fromString("12345678-1234-5678-1234-567812345678"),
                "PlayerOne",
                List.of(new MinecraftSessionVerifier.Property("textures", "value", "signature"))));

        var response = VelocityModernForwarding.response(
                UnpooledByteBufAllocatorHolder.ALLOC,
                7,
                new MinecraftForwardingRuntime("velocity-modern", "secret"),
                identity);
        try {
            var payload = unwrapFrame(response);
            try {
                assertEquals(0x02, MinecraftVarInts.read(payload));
                assertEquals(7, MinecraftVarInts.read(payload));
                assertTrue(payload.readBoolean());
                var signature = new byte[32];
                payload.readBytes(signature);
                assertFalse(allZero(signature));
                assertEquals(1, MinecraftVarInts.read(payload));
                assertEquals("203.0.113.10", readString(payload));
                assertArrayEquals(uuidBytes(UUID.fromString("12345678-1234-5678-1234-567812345678")), readBytes(payload, 16));
                assertEquals("PlayerOne", readString(payload));
                assertEquals(1, MinecraftVarInts.read(payload));
                assertEquals("textures", readString(payload));
                assertEquals("value", readString(payload));
                assertTrue(payload.readBoolean());
                assertEquals("signature", readString(payload));
            } finally {
                payload.release();
            }
        } finally {
            response.release();
        }
    }

    @Test
    void backendRelayInterceptsVelocityRequestAndDoesNotForwardToFrontend() {
        var metrics = new ProxyMetrics();
        var frontend = new EmbeddedChannel();
        var identity = new RelaySessionIdentity("/203.0.113.10:50000");
        identity.playerName("PlayerOne");
        var backend = new EmbeddedChannel(new BackendRelayHandler(
                frontend,
                metrics,
                "lobby-1",
                4096,
                new MinecraftCompressionAuditState(4096),
                CompressionRuntime.defaults(),
                identity,
                new MinecraftForwardingRuntime("velocity-modern", "secret"),
                false,
                25));

        assertFalse(backend.writeInbound(pluginRequest(9)));

        assertNull(frontend.readOutbound());
        var response = (ByteBuf) backend.readOutbound();
        try {
            var payload = unwrapFrame(response);
            try {
                assertEquals(0x02, MinecraftVarInts.read(payload));
                assertEquals(9, MinecraftVarInts.read(payload));
                assertTrue(payload.readBoolean());
            } finally {
                payload.release();
            }
        } finally {
            response.release();
            backend.finishAndReleaseAll();
            frontend.finishAndReleaseAll();
        }
    }

    private static ByteBuf pluginRequest(int messageId) {
        var payload = Unpooled.buffer();
        MinecraftVarInts.write(payload, 0x04);
        MinecraftVarInts.write(payload, messageId);
        writeString(payload, VelocityModernForwarding.CHANNEL);
        MinecraftVarInts.write(payload, 1);
        return frame(payload);
    }

    private static ByteBuf frame(ByteBuf payload) {
        var frame = Unpooled.buffer();
        MinecraftVarInts.write(frame, payload.readableBytes());
        frame.writeBytes(payload);
        payload.release();
        return frame;
    }

    private static ByteBuf unwrapFrame(ByteBuf frame) {
        var duplicate = frame.retainedDuplicate();
        try {
            var length = MinecraftVarInts.read(duplicate);
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

    private static String readString(ByteBuf input) {
        var length = MinecraftVarInts.read(input);
        var bytes = readBytes(input, length);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(ByteBuf input, int length) {
        var bytes = new byte[length];
        input.readBytes(bytes);
        return bytes;
    }

    private static byte[] uuidBytes(UUID uuid) {
        var buffer = Unpooled.buffer(16);
        try {
            buffer.writeLong(uuid.getMostSignificantBits());
            buffer.writeLong(uuid.getLeastSignificantBits());
            return readBytes(buffer, 16);
        } finally {
            buffer.release();
        }
    }

    private static boolean allZero(byte[] bytes) {
        for (var value : bytes) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static final class UnpooledByteBufAllocatorHolder {
        private static final io.netty.buffer.ByteBufAllocator ALLOC = io.netty.buffer.ByteBufAllocator.DEFAULT;
    }
}

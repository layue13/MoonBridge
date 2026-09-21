package dev.strataproxy.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftPlayMessagesTest {
    @Test
    void buildsLegacyChatPacketForProtocol5() {
        var frame = MinecraftPlayMessages.systemChatFrame(
                UnpooledByteBufAllocator.DEFAULT,
                5,
                "Servers: survival-1",
                false,
                0);
        assertTrue(frame.isPresent());
        try {
            var decoded = decodeFrame(frame.get());
            var packetId = MinecraftProtocolCodec.readVarInt(decoded);
            assertEquals(0x02, packetId);
            var message = MinecraftProtocolCodec.readString(decoded, 32767);
            assertEquals("{\"text\":\"Servers: survival-1\"}", message);
            assertFalse(decoded.isReadable());
        } finally {
            frame.ifPresent(ByteBuf::release);
        }
    }

    @Test
    void supportsCurrentProtocolSystemChatPacket() {
        var frame = MinecraftPlayMessages.systemChatFrame(
                UnpooledByteBufAllocator.DEFAULT,
                763,
                "ok",
                false,
                0);
        assertTrue(frame.isPresent());
        try {
            var decoded = decodeFrame(frame.get());
            try {
                var packetId = MinecraftProtocolCodec.readVarInt(decoded);
                assertEquals(0x64, packetId);
                assertEquals("{\"text\":\"ok\"}", MinecraftProtocolCodec.readString(decoded, 32767));
                assertEquals((byte) 0, decoded.readByte());
                assertFalse(decoded.isReadable());
            } finally {
                decoded.release();
            }
        } finally {
            frame.ifPresent(ByteBuf::release);
        }
    }

    @Test
    void ignoresUnsupportedProtocolForSystemChatPacket() {
        var frame = MinecraftPlayMessages.systemChatFrame(
                UnpooledByteBufAllocator.DEFAULT,
                1,
                "unsupported",
                false,
                0);
        assertEquals(Optional.empty(), frame);
    }

    private static ByteBuf decodeFrame(ByteBuf frame) {
        var frameLength = MinecraftProtocolCodec.readVarInt(frame);
        return frame.readRetainedSlice(frameLength);
    }
}

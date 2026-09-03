package dev.strataproxy.network;

import dev.strataproxy.codec.minecraft.MinecraftCompressionCodec;
import dev.strataproxy.codec.minecraft.MinecraftVarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftPluginChannelRegistryTest {
    @Test
    void tracksServerboundRegisterAndUnregisterChannels() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var registry = new MinecraftPluginChannelRegistry(4096);
        try {
            var register = customPayload(0x17, "REGISTER", "FML|HS\0FML\0MYMOD");
            try {
                registry.observeServerbound(register, profile);
            } finally {
                register.release();
            }

            assertEquals(Set.of("FML|HS", "FML", "MYMOD"), registry.channels());

            var unregister = customPayload(0x17, "UNREGISTER", "MYMOD");
            try {
                registry.observeServerbound(unregister, profile);
            } finally {
                unregister.release();
            }

            assertEquals(Set.of("FML|HS", "FML"), registry.channels());
        } finally {
            registry.close();
        }
    }

    @Test
    void buildsLegacyServerboundRegisterFrameFromCurrentChannels() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var registry = new MinecraftPluginChannelRegistry(4096);
        try {
            var register = customPayload(0x17, "REGISTER", "FML|HS\0FML\0FML|MP\0FORGE");
            try {
                registry.observeServerbound(register, profile);
            } finally {
                register.release();
            }

            var frame = registry.registrationFrame(UnpooledByteBufAllocator.DEFAULT, profile);
            try {
                assertServerboundRegister(frame, "FML|HS\0FML\0FML|MP\0FORGE");
            } finally {
                release(frame);
            }
        } finally {
            registry.close();
        }
    }

    @Test
    void observesSplitFrames() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var registry = new MinecraftPluginChannelRegistry(4096);
        var frame = customPayload(0x17, "REGISTER", "FML|HS\0FML");
        try {
            var first = frame.readRetainedSlice(2);
            var second = frame.readRetainedSlice(frame.readableBytes());
            try {
                registry.observeServerbound(first, profile);
                assertTrue(registry.channels().isEmpty());

                registry.observeServerbound(second, profile);
                assertEquals(Set.of("FML|HS", "FML"), registry.channels());
            } finally {
                release(first);
                release(second);
            }
        } finally {
            frame.release();
            registry.close();
        }
    }

    @Test
    void tracksCompressedServerboundRegisterChannels() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_8);
        var registry = new MinecraftPluginChannelRegistry(4096);
        try {
            var register = compressedCustomPayload18("REGISTER", "FML|HS\0FML\0FML|MP\0FORGE", 0);
            try {
                registry.observeServerboundCompressed(UnpooledByteBufAllocator.DEFAULT, register, 0, profile);
            } finally {
                register.release();
            }

            assertEquals(Set.of("FML|HS", "FML", "FML|MP", "FORGE"), registry.channels());

            var frame = registry.registrationFrame(UnpooledByteBufAllocator.DEFAULT, profile);
            try {
                assertServerboundRegisterRemaining(frame, "FML|HS\0FML\0FML|MP\0FORGE");
            } finally {
                release(frame);
            }
        } finally {
            registry.close();
        }
    }

    @Test
    void observesSplitCompressedFramesAfterFirstInputIsReleased() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_8);
        var registry = new MinecraftPluginChannelRegistry(4096);
        var frame = compressedCustomPayload18("REGISTER", "FML|HS\0FML", 0);
        try {
            var first = frame.readRetainedSlice(2);
            var second = frame.readRetainedSlice(frame.readableBytes());
            try {
                registry.observeServerboundCompressed(UnpooledByteBufAllocator.DEFAULT, first, 0, profile);
            } finally {
                first.release();
            }

            try {
                registry.observeServerboundCompressed(UnpooledByteBufAllocator.DEFAULT, second, 0, profile);
                assertEquals(Set.of("FML|HS", "FML"), registry.channels());
            } finally {
                second.release();
            }
        } finally {
            frame.release();
            registry.close();
        }
    }

    private static ByteBuf customPayload(int packetId, String channel, String payloadText) {
        var payload = Unpooled.wrappedBuffer(payloadText.getBytes(StandardCharsets.UTF_8));
        var packet = Unpooled.buffer();
        try {
            MinecraftVarInts.write(packet, packetId);
            writeString(packet, channel);
            packet.writeShort(payload.readableBytes());
            packet.writeBytes(payload, payload.readerIndex(), payload.readableBytes());
            return frame(packet);
        } finally {
            payload.release();
        }
    }

    private static ByteBuf compressedCustomPayload18(String channel, String payloadText, int threshold) {
        var payload = Unpooled.wrappedBuffer(payloadText.getBytes(StandardCharsets.UTF_8));
        var packet = Unpooled.buffer();
        try (var codec = new MinecraftCompressionCodec()) {
            MinecraftVarInts.write(packet, 0x17);
            writeString(packet, channel);
            packet.writeBytes(payload, payload.readerIndex(), payload.readableBytes());
            return codec.encodeFrame(UnpooledByteBufAllocator.DEFAULT, packet, threshold);
        } finally {
            payload.release();
            packet.release();
        }
    }

    private static ByteBuf frame(ByteBuf packet) {
        var frame = Unpooled.buffer();
        MinecraftVarInts.write(frame, packet.readableBytes());
        frame.writeBytes(packet, packet.readerIndex(), packet.readableBytes());
        packet.release();
        return frame;
    }

    private static void assertServerboundRegister(ByteBuf frame, String expectedChannels) {
        var packet = payload(frame);
        try {
            assertEquals(0x17, MinecraftVarInts.read(packet));
            assertEquals("REGISTER", MinecraftProtocolCodec.readString(packet, 32767));
            var length = packet.readUnsignedShort();
            assertEquals(expectedChannels, packet.readCharSequence(length, StandardCharsets.UTF_8).toString());
            assertEquals(0, packet.readableBytes());
        } finally {
            packet.release();
        }
    }

    private static void assertServerboundRegisterRemaining(ByteBuf frame, String expectedChannels) {
        var packet = payload(frame);
        try {
            assertEquals(0x17, MinecraftVarInts.read(packet));
            assertEquals("REGISTER", MinecraftProtocolCodec.readString(packet, 32767));
            assertEquals(expectedChannels, packet.readCharSequence(packet.readableBytes(), StandardCharsets.UTF_8).toString());
            assertEquals(0, packet.readableBytes());
        } finally {
            packet.release();
        }
    }

    private static ByteBuf payload(ByteBuf frame) {
        var length = MinecraftVarInts.read(frame);
        return frame.readRetainedSlice(length);
    }

    private static void writeString(ByteBuf output, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        MinecraftVarInts.write(output, bytes.length);
        output.writeBytes(bytes);
    }

    private static void release(ByteBuf buffer) {
        if (buffer != null && buffer.refCnt() > 0) {
            buffer.release();
        }
    }
}

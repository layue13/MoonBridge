package dev.strataproxy.network;

import dev.strataproxy.codec.minecraft.MinecraftVarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftCompressionNegotiationDetectorTest {
    @Test
    void legacy1710DoesNotNegotiateVanillaLoginCompression() {
        var detector = new MinecraftCompressionNegotiationDetector(
                4096,
                MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10));
        var playSetCompressionLikeFrame = frame(packetWithVarInt(0x46, 256));
        try {
            assertTrue(detector.complete());
            assertTrue(detector.observe(playSetCompressionLikeFrame).isEmpty());
        } finally {
            playSetCompressionLikeFrame.release();
            detector.close();
        }
    }

    @Test
    void legacy18NegotiatesLoginCompressionBeforeLoginSuccess() {
        var detector = new MinecraftCompressionNegotiationDetector(
                4096,
                MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_8));
        var setCompression = frame(packetWithVarInt(0x03, 256));
        try {
            var threshold = detector.observe(setCompression);

            assertTrue(threshold.isPresent());
            assertTrue(detector.complete());
        } finally {
            setCompression.release();
            detector.close();
        }
    }

    @Test
    void legacy18StopsProbingAfterLoginSuccess() {
        var detector = new MinecraftCompressionNegotiationDetector(
                4096,
                MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_8));
        var loginSuccess = frame(packetWithVarInt(0x02));
        try {
            assertTrue(detector.observe(loginSuccess).isEmpty());
            assertTrue(detector.complete());
        } finally {
            loginSuccess.release();
            detector.close();
        }
    }

    @Test
    void legacy18WaitsForSplitCompressionFrame() {
        var detector = new MinecraftCompressionNegotiationDetector(
                4096,
                MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_8));
        var setCompression = frame(packetWithVarInt(0x03, 512));
        var first = setCompression.readRetainedSlice(1);
        var second = setCompression.readRetainedSlice(setCompression.readableBytes());
        try {
            assertTrue(detector.observe(first).isEmpty());
            assertFalse(detector.complete());

            var threshold = detector.observe(second);

            assertTrue(threshold.isPresent());
            assertTrue(detector.complete());
        } finally {
            first.release();
            second.release();
            setCompression.release();
            detector.close();
        }
    }

    private static ByteBuf packetWithVarInt(int packetId, int... values) {
        var packet = Unpooled.buffer();
        MinecraftVarInts.write(packet, packetId);
        for (var value : values) {
            MinecraftVarInts.write(packet, value);
        }
        return packet;
    }

    private static ByteBuf frame(ByteBuf packet) {
        var frame = Unpooled.buffer();
        MinecraftVarInts.write(frame, packet.readableBytes());
        frame.writeBytes(packet, packet.readerIndex(), packet.readableBytes());
        packet.release();
        return frame;
    }
}

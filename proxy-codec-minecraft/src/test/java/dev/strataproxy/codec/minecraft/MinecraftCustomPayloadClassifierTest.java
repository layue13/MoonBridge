package dev.strataproxy.codec.minecraft;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftCustomPayloadClassifierTest {
    @Test
    void classifiesBrandPayloadWithoutConsumingInput() {
        var frame = customPayload(0x01, "minecraft:brand", 12);
        var readerIndex = frame.readerIndex();
        try {
            var classification = MinecraftCustomPayloadClassifier.classify(frame, 1024);

            assertEquals(readerIndex, frame.readerIndex());
            assertEquals(0x01, classification.packetId());
            assertEquals("minecraft:brand", classification.channel());
            assertEquals(12, classification.payloadBytes());
            assertEquals(MinecraftCustomPayloadClassifier.CustomPayloadKind.BRAND, classification.kind());
        } finally {
            frame.release();
        }
    }

    @Test
    void classifiesForgeAndFabricPayloads() {
        var forge = customPayload(0x01, "fml:handshake", 64);
        var fabric = customPayload(0x01, "fabric:registry/sync", 64);
        try {
            assertEquals(
                    MinecraftCustomPayloadClassifier.CustomPayloadKind.FORGE_HANDSHAKE,
                    MinecraftCustomPayloadClassifier.classify(forge, 1024).kind());
            assertEquals(
                    MinecraftCustomPayloadClassifier.CustomPayloadKind.FABRIC_HANDSHAKE,
                    MinecraftCustomPayloadClassifier.classify(fabric, 1024).kind());
        } finally {
            forge.release();
            fabric.release();
        }
    }

    @Test
    void classifiesLegacyForgeChannelsWithShortPayloadLength() {
        var fmlHandshake = legacyCustomPayload(0x17, "FML|HS", 64);
        var fmlMultipart = legacyCustomPayload(0x17, "FML|MP", 32);
        var register = legacyCustomPayload(0x17, "REGISTER", 16);
        try {
            var handshake = MinecraftCustomPayloadClassifier.classify(
                    fmlHandshake,
                    1024,
                    MinecraftCustomPayloadClassifier.PayloadLengthFormat.UNSIGNED_SHORT);
            var multipart = MinecraftCustomPayloadClassifier.classify(
                    fmlMultipart,
                    1024,
                    MinecraftCustomPayloadClassifier.PayloadLengthFormat.UNSIGNED_SHORT);
            var registration = MinecraftCustomPayloadClassifier.classify(
                    register,
                    1024,
                    MinecraftCustomPayloadClassifier.PayloadLengthFormat.UNSIGNED_SHORT);

            assertEquals(0x17, handshake.packetId());
            assertEquals("FML|HS", handshake.channel());
            assertEquals(64, handshake.payloadBytes());
            assertEquals(MinecraftCustomPayloadClassifier.CustomPayloadKind.FORGE_HANDSHAKE, handshake.kind());
            assertEquals(MinecraftCustomPayloadClassifier.CustomPayloadKind.FORGE_HANDSHAKE, multipart.kind());
            assertEquals(MinecraftCustomPayloadClassifier.CustomPayloadKind.FORGE_HANDSHAKE, registration.kind());
        } finally {
            fmlHandshake.release();
            fmlMultipart.release();
            register.release();
        }
    }

    @Test
    void classifiesLoginPluginRequestWithoutConsumingInput() {
        var frame = loginPluginRequest(0x04, 7, "fml:handshake", 128);
        var readerIndex = frame.readerIndex();
        try {
            var classification = MinecraftCustomPayloadClassifier.classifyLoginPluginRequest(frame, 64);

            assertEquals(readerIndex, frame.readerIndex());
            assertEquals(0x04, classification.packetId());
            assertEquals("fml:handshake", classification.channel());
            assertEquals(128, classification.payloadBytes());
            assertEquals(MinecraftCustomPayloadClassifier.CustomPayloadKind.FORGE_HANDSHAKE, classification.kind());
            assertTrue(classification.largePayload());
        } finally {
            frame.release();
        }
    }

    @Test
    void classifiesRegistryOrConfigSyncPayloads() {
        var frame = customPayload(0x01, "modded:registry_sync", 64);
        try {
            assertEquals(
                    MinecraftCustomPayloadClassifier.CustomPayloadKind.REGISTRY_OR_CONFIG_SYNC,
                    MinecraftCustomPayloadClassifier.classify(frame, 1024).kind());
        } finally {
            frame.release();
        }
    }

    @Test
    void flagsLargePayloads() {
        var frame = customPayload(0x01, "unknown:large", 2048);
        try {
            var classification = MinecraftCustomPayloadClassifier.classify(frame, 1024);

            assertEquals(MinecraftCustomPayloadClassifier.CustomPayloadKind.UNKNOWN, classification.kind());
            assertTrue(classification.largePayload());
        } finally {
            frame.release();
        }
    }

    @Test
    void rejectsOversizedChannelName() {
        var frame = customPayload(0x01, "x".repeat(129), 1);
        try {
            assertThrows(MinecraftCodecException.class, () -> MinecraftCustomPayloadClassifier.classify(frame, 1024));
        } finally {
            frame.release();
        }
    }

    private static io.netty.buffer.ByteBuf customPayload(int packetId, String channel, int payloadBytes) {
        var frame = Unpooled.buffer();
        MinecraftVarInts.write(frame, packetId);
        var channelBytes = channel.getBytes(StandardCharsets.UTF_8);
        MinecraftVarInts.write(frame, channelBytes.length);
        frame.writeBytes(channelBytes);
        frame.writeZero(payloadBytes);
        return frame;
    }

    private static io.netty.buffer.ByteBuf legacyCustomPayload(int packetId, String channel, int payloadBytes) {
        var frame = Unpooled.buffer();
        MinecraftVarInts.write(frame, packetId);
        var channelBytes = channel.getBytes(StandardCharsets.UTF_8);
        MinecraftVarInts.write(frame, channelBytes.length);
        frame.writeBytes(channelBytes);
        frame.writeShort(payloadBytes);
        frame.writeZero(payloadBytes);
        return frame;
    }

    private static io.netty.buffer.ByteBuf loginPluginRequest(int packetId, int messageId, String channel, int payloadBytes) {
        var frame = Unpooled.buffer();
        MinecraftVarInts.write(frame, packetId);
        MinecraftVarInts.write(frame, messageId);
        var channelBytes = channel.getBytes(StandardCharsets.UTF_8);
        MinecraftVarInts.write(frame, channelBytes.length);
        frame.writeBytes(channelBytes);
        frame.writeZero(payloadBytes);
        return frame;
    }
}

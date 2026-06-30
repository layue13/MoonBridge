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
}

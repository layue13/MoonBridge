package dev.strataproxy.network;

import dev.strataproxy.analysis.CustomPayloadAnomalyPolicy;
import dev.strataproxy.codec.minecraft.MinecraftCustomPayloadClassifier;
import dev.strataproxy.codec.minecraft.MinecraftVarInts;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftLegacyCustomPayloadSamplerTest {
    @Test
    void observesLegacyForgeHandshakePayload() {
        var sampler = new MinecraftCustomPayloadInspectionSampler(
                4096,
                1024,
                CustomPayloadAnomalyPolicy.defaults(),
                MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10));
        var frame = legacyCustomPayloadFrame(0x17, "FML|HS", new byte[] {0, 2, 0, 0, 0, 0});
        try {
            var results = sampler.observe(frame);

            assertEquals(1, results.size());
            var classification = results.get(0).classification();
            assertEquals(0x17, classification.packetId());
            assertEquals("FML|HS", classification.channel());
            assertEquals(6, classification.payloadBytes());
            assertEquals(MinecraftCustomPayloadClassifier.CustomPayloadKind.FORGE_HANDSHAKE, classification.kind());
        } finally {
            sampler.close();
            frame.release();
        }
    }

    @Test
    void ignoresLegacyCustomPayloadPacketIdOnModernProfile() {
        var sampler = MinecraftCustomPayloadInspectionSampler.defaults(4096);
        var frame = legacyCustomPayloadFrame(0x17, "FML|HS", new byte[] {0, 2});
        try {
            assertTrue(sampler.observe(frame).isEmpty());
        } finally {
            sampler.close();
            frame.release();
        }
    }

    @Test
    void parsesLegacyBungeeConnectPayload() {
        var sampler = new BungeeConnectRequestSampler(
                4096,
                MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10));
        var payload = Unpooled.buffer();
        try {
            writeUnsignedShortString(payload, "Connect");
            writeUnsignedShortString(payload, "survival-1");
            var bytes = new byte[payload.readableBytes()];
            payload.getBytes(payload.readerIndex(), bytes);
            var frame = legacyCustomPayloadFrame(0x17, "BungeeCord", bytes);
            try {
                var requests = sampler.observeUncompressed(frame);

                assertEquals(1, requests.size());
                assertEquals(0x17, requests.get(0).packetId());
                assertEquals("BungeeCord", requests.get(0).channel());
                assertEquals("survival-1", requests.get(0).targetServer());
            } finally {
                frame.release();
            }
        } finally {
            sampler.close();
            payload.release();
        }
    }

    private static io.netty.buffer.ByteBuf legacyCustomPayloadFrame(int packetId, String channel, byte[] payload) {
        var packet = Unpooled.buffer();
        MinecraftVarInts.write(packet, packetId);
        writeMinecraftString(packet, channel);
        packet.writeShort(payload.length);
        packet.writeBytes(payload);
        var frame = Unpooled.buffer();
        MinecraftVarInts.write(frame, packet.readableBytes());
        frame.writeBytes(packet);
        packet.release();
        return frame;
    }

    private static void writeMinecraftString(io.netty.buffer.ByteBuf output, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        MinecraftVarInts.write(output, bytes.length);
        output.writeBytes(bytes);
    }

    private static void writeUnsignedShortString(io.netty.buffer.ByteBuf output, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeShort(bytes.length);
        output.writeBytes(bytes);
    }
}

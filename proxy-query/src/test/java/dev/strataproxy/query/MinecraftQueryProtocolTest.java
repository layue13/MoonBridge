package dev.strataproxy.query;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftQueryProtocolTest {
    @Test
    void encodesStatusHandshakeAndRequest() {
        var handshake = MinecraftQueryProtocol.statusHandshake(763, "play.example.net", 25565);
        var request = MinecraftQueryProtocol.statusRequest();

        assertTrue(handshake.length > 0);
        assertEquals(2, request.length);
        assertEquals(1, request[0]);
        assertEquals(0, request[1]);
    }

    @Test
    void encodesLoginHandshakeWithLoginNextState() throws Exception {
        var handshake = MinecraftQueryProtocol.loginHandshake(763, "play.example.net", 25565);
        var input = new java.io.DataInputStream(new ByteArrayInputStream(handshake));

        var frame = input.readNBytes(readVarInt(input));
        var cursor = new java.io.DataInputStream(new ByteArrayInputStream(frame));

        assertEquals(0, readVarInt(cursor));
        assertEquals(763, readVarInt(cursor));
        readString(cursor);
        cursor.readUnsignedShort();
        assertEquals(2, readVarInt(cursor));
    }

    @Test
    void encodesRawPacketFrame() throws Exception {
        var packet = MinecraftQueryProtocol.rawPacketFrame(7, 5);
        var input = new java.io.DataInputStream(new ByteArrayInputStream(packet));
        var frame = input.readNBytes(readVarInt(input));
        var cursor = new java.io.DataInputStream(new ByteArrayInputStream(frame));

        assertEquals(7, readVarInt(cursor));
        assertEquals(5, cursor.readNBytes(8).length);
    }

    @Test
    void encodesCompressedPacketFrame() throws Exception {
        var packet = MinecraftQueryProtocol.compressedPacketFrame(7, 512, 64);
        var input = new java.io.DataInputStream(new ByteArrayInputStream(packet));
        var frame = input.readNBytes(readVarInt(input));
        var cursor = new java.io.DataInputStream(new ByteArrayInputStream(frame));

        assertEquals(513, readVarInt(cursor));
        var compressed = cursor.readAllBytes();
        var inflated = inflate(compressed);
        var inflatedInput = new java.io.DataInputStream(new ByteArrayInputStream(inflated));
        assertEquals(7, readVarInt(inflatedInput));
        assertEquals(512, inflatedInput.readNBytes(1024).length);
    }

    @Test
    void readsCompressionThresholdFrame() throws Exception {
        var payload = new ByteArrayOutputStream();
        MinecraftQueryProtocol.writeVarInt(payload, 3);
        MinecraftQueryProtocol.writeVarInt(payload, 256);
        var frame = new ByteArrayOutputStream();
        MinecraftQueryProtocol.writeVarInt(frame, payload.size());
        frame.writeBytes(payload.toByteArray());

        assertEquals(256, MinecraftQueryProtocol.readCompressionThreshold(
                new ByteArrayInputStream(frame.toByteArray()),
                1024));
    }

    @Test
    void decodesStatusResponseJson() throws Exception {
        var json = "{\"version\":{\"name\":\"test\",\"protocol\":763}}";
        var payload = new ByteArrayOutputStream();
        MinecraftQueryProtocol.writeVarInt(payload, 0);
        var jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        MinecraftQueryProtocol.writeVarInt(payload, jsonBytes.length);
        payload.writeBytes(jsonBytes);

        var frame = new ByteArrayOutputStream();
        MinecraftQueryProtocol.writeVarInt(frame, payload.size());
        frame.writeBytes(payload.toByteArray());

        assertEquals(json, MinecraftQueryProtocol.readStatusResponse(new ByteArrayInputStream(frame.toByteArray())));
    }

    private static String readString(java.io.DataInputStream input) throws Exception {
        var bytes = input.readNBytes(readVarInt(input));
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int readVarInt(java.io.DataInputStream input) throws Exception {
        var value = 0;
        var position = 0;
        while (position < 5) {
            var current = input.readUnsignedByte();
            value |= (current & 0x7F) << (position * 7);
            position++;
            if ((current & 0x80) == 0) {
                return value;
            }
        }
        throw new IllegalArgumentException("malformed VarInt");
    }

    private static byte[] inflate(byte[] input) throws Exception {
        var inflater = new Inflater();
        try {
            inflater.setInput(input);
            var output = new ByteArrayOutputStream();
            var buffer = new byte[1024];
            while (!inflater.finished()) {
                var bytes = inflater.inflate(buffer);
                output.write(buffer, 0, bytes);
            }
            return output.toByteArray();
        } finally {
            inflater.end();
        }
    }
}

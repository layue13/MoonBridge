package dev.strataproxy.query;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;

final class MinecraftQueryProtocol {
    private static final int MAX_VAR_INT_BYTES = 5;
    private static final int MAX_STATUS_BYTES = 2 * 1024 * 1024;

    private MinecraftQueryProtocol() {
    }

    static byte[] statusHandshake(int protocolVersion, String host, int port) {
        return handshake(protocolVersion, host, port, 1);
    }

    static byte[] loginHandshake(int protocolVersion, String host, int port) {
        return handshake(protocolVersion, host, port, 2);
    }

    private static byte[] handshake(int protocolVersion, String host, int port, int nextState) {
        var payload = new ByteArrayOutputStream();
        writeVarInt(payload, 0);
        writeVarInt(payload, protocolVersion);
        writeString(payload, host);
        payload.write((port >>> 8) & 0xFF);
        payload.write(port & 0xFF);
        writeVarInt(payload, nextState);
        return frame(payload.toByteArray());
    }

    static byte[] statusRequest() {
        var payload = new ByteArrayOutputStream();
        writeVarInt(payload, 0);
        return frame(payload.toByteArray());
    }

    static byte[] loginStart(String username) {
        var payload = new ByteArrayOutputStream();
        writeVarInt(payload, 0);
        writeString(payload, username);
        return frame(payload.toByteArray());
    }

    static byte[] rawPacketFrame(int packetId, int payloadBytes) {
        if (packetId < 0) {
            throw new IllegalArgumentException("packetId must be non-negative");
        }
        if (payloadBytes < 0) {
            throw new IllegalArgumentException("payloadBytes must be non-negative");
        }
        var payload = new ByteArrayOutputStream();
        writeVarInt(payload, packetId);
        payload.writeBytes(new byte[payloadBytes]);
        return frame(payload.toByteArray());
    }

    static byte[] compressedPacketFrame(int packetId, int payloadBytes, int threshold) {
        if (threshold < 0) {
            throw new IllegalArgumentException("threshold must be non-negative");
        }
        var payload = new ByteArrayOutputStream();
        writeVarInt(payload, packetId);
        payload.writeBytes(new byte[payloadBytes]);
        var packet = payload.toByteArray();
        var body = new ByteArrayOutputStream();
        if (packet.length < threshold) {
            writeVarInt(body, 0);
            body.writeBytes(packet);
        } else {
            writeVarInt(body, packet.length);
            body.writeBytes(deflate(packet));
        }
        return frame(body.toByteArray());
    }

    static int readCompressionThreshold(InputStream input, int maxFrameBytes) throws IOException {
        var data = new DataInputStream(input);
        var frameLength = readVarInt(data);
        if (frameLength <= 0 || frameLength > maxFrameBytes) {
            throw new IOException("compression negotiation frame length out of bounds: " + frameLength);
        }
        var frame = data.readNBytes(frameLength);
        if (frame.length != frameLength) {
            throw new EOFException("truncated compression negotiation frame");
        }
        var cursor = new Cursor(frame);
        var packetId = readVarInt(cursor);
        if (packetId != 3) {
            throw new IOException("unexpected compression negotiation packet id: " + packetId);
        }
        return readVarInt(cursor);
    }

    static String readStatusResponse(InputStream input) throws IOException {
        var data = new DataInputStream(input);
        var frameLength = readVarInt(data);
        if (frameLength <= 0 || frameLength > MAX_STATUS_BYTES) {
            throw new IOException("status response frame length out of bounds: " + frameLength);
        }
        var frame = data.readNBytes(frameLength);
        if (frame.length != frameLength) {
            throw new EOFException("truncated status response");
        }
        var cursor = new Cursor(frame);
        var packetId = readVarInt(cursor);
        if (packetId != 0) {
            throw new IOException("unexpected status packet id: " + packetId);
        }
        var jsonLength = readVarInt(cursor);
        if (jsonLength < 0 || jsonLength > cursor.remaining()) {
            throw new IOException("status json length out of bounds: " + jsonLength);
        }
        return new String(frame, cursor.position(), jsonLength, StandardCharsets.UTF_8);
    }

    static void writeVarInt(ByteArrayOutputStream output, int value) {
        var current = value;
        do {
            var temp = current & 0x7F;
            current >>>= 7;
            if (current != 0) {
                temp |= 0x80;
            }
            output.write(temp);
        } while (current != 0);
    }

    private static byte[] frame(byte[] payload) {
        var frame = new ByteArrayOutputStream();
        writeVarInt(frame, payload.length);
        frame.writeBytes(payload);
        return frame.toByteArray();
    }

    private static byte[] deflate(byte[] input) {
        var deflater = new Deflater();
        try {
            deflater.setInput(input);
            deflater.finish();
            var output = new ByteArrayOutputStream(Math.max(64, input.length / 2));
            var buffer = new byte[8192];
            while (!deflater.finished()) {
                var bytes = deflater.deflate(buffer);
                output.write(buffer, 0, bytes);
            }
            return output.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static void writeString(ByteArrayOutputStream output, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length);
        output.writeBytes(bytes);
    }

    private static int readVarInt(DataInputStream input) throws IOException {
        var value = 0;
        var position = 0;
        while (position < MAX_VAR_INT_BYTES) {
            var current = input.readUnsignedByte();
            value |= (current & 0x7F) << (position * 7);
            position++;
            if ((current & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("malformed VarInt");
    }

    private static int readVarInt(Cursor input) throws IOException {
        var value = 0;
        var position = 0;
        while (position < MAX_VAR_INT_BYTES) {
            if (input.remaining() <= 0) {
                throw new EOFException("truncated VarInt");
            }
            var current = input.readUnsignedByte();
            value |= (current & 0x7F) << (position * 7);
            position++;
            if ((current & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("malformed VarInt");
    }

    private static final class Cursor {
        private final byte[] data;
        private int position;

        private Cursor(byte[] data) {
            this.data = data;
        }

        private int readUnsignedByte() {
            return data[position++] & 0xFF;
        }

        private int position() {
            return position;
        }

        private int remaining() {
            return data.length - position;
        }
    }
}

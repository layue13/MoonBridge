package dev.strataproxy.backend.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Length-framed binary transport for authenticated agent streams. */
public final class ChannelWire {
    public static final int MAX_FRAME_BYTES = 64 * 1024;
    public static final int MAX_PAYLOAD_BYTES = 48 * 1024;
    private static final int VERSION = 1;

    private ChannelWire() { }

    public static ChannelFrame read(InputStream input) throws IOException {
        DataInputStream stream = new DataInputStream(input);
        int length;
        try {
            length = stream.readInt();
        } catch (EOFException exception) {
            return null;
        }
        if (length < 1 || length > MAX_FRAME_BYTES) throw new IOException("invalid agent frame length");
        byte[] bytes = new byte[length];
        stream.readFully(bytes);
        DataInputStream frame = new DataInputStream(new ByteArrayInputStream(bytes));
        if (frame.readUnsignedByte() != VERSION) throw new IOException("unsupported agent frame version");
        byte type = frame.readByte();
        String requestId = shortText(frame);
        String messageId = shortText(frame);
        String correlationId = shortText(frame);
        String idempotencyKey = shortText(frame);
        String source = shortText(frame);
        String channel = shortText(frame);
        String mode = shortText(frame);
        String outcome = shortText(frame);
        int payloadLength = frame.readInt();
        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_BYTES || payloadLength > frame.available()) {
            throw new IOException("invalid agent payload length");
        }
        byte[] payload = new byte[payloadLength];
        frame.readFully(payload);
        if (frame.available() != 0) throw new IOException("trailing agent frame data");
        return new ChannelFrame(type, requestId, messageId, correlationId, idempotencyKey, source, channel, mode, outcome, payload);
    }

    public static void write(OutputStream output, ChannelFrame message) throws IOException {
        if (message.payload.length > MAX_PAYLOAD_BYTES) throw new IOException("agent payload too large");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream frame = new DataOutputStream(bytes);
        frame.writeByte(VERSION);
        frame.writeByte(message.type);
        writeText(frame, message.requestId);
        writeText(frame, message.messageId);
        writeText(frame, message.correlationId);
        writeText(frame, message.idempotencyKey);
        writeText(frame, message.source);
        writeText(frame, message.channel);
        writeText(frame, message.mode);
        writeText(frame, message.outcome);
        frame.writeInt(message.payload.length);
        frame.write(message.payload);
        frame.flush();
        if (bytes.size() > MAX_FRAME_BYTES) throw new IOException("agent frame too large");
        DataOutputStream stream = new DataOutputStream(output);
        stream.writeInt(bytes.size());
        bytes.writeTo(stream);
        stream.flush();
    }

    private static String shortText(DataInputStream frame) throws IOException {
        String value = frame.readUTF();
        if (value.length() > 256) throw new IOException("agent frame field too long");
        return value;
    }

    private static void writeText(DataOutputStream frame, String value) throws IOException {
        String text = value == null ? "" : value;
        if (text.length() > 256) throw new IOException("agent frame field too long");
        frame.writeUTF(text);
    }
}

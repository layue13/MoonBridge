package dev.moonbridge.core.control;

import dev.moonbridge.messaging.protocol.MessageCodec;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static dev.moonbridge.core.control.ControlFailures.*;
import static dev.moonbridge.core.control.ControlMessages.*;
import static dev.moonbridge.core.control.ControlProtocol.*;

/** Wire constants and primitive framing for the backend control socket. */
final class ControlProtocol {
    static final int VERSION = 3;
    static final int MAX_FRAME = MessageCodec.MAX_FRAME_BYTES;
    static final int HELLO = 1, REGISTER = 2, REGISTERED = 3, HEARTBEAT = 4, PONG = 5, GOODBYE = 6;

    private ControlProtocol() { }

    record Frame(int type, DataInputStream input, byte[] bytes) { }

    static Frame readFrame(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > MAX_FRAME) throw new IOException("invalid backend control frame length");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException();
        DataInputStream body = new DataInputStream(new ByteArrayInputStream(bytes));
        return new Frame(body.readUnsignedByte(), body, bytes);
    }

    static String readString(DataInputStream input, int maximum) throws IOException {
        int length = input.readUnsignedShort();
        if (length > maximum) throw new IOException("control string too long");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException();
        String decoded = new String(bytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(decoded.getBytes(StandardCharsets.UTF_8), bytes)) throw new IOException("invalid UTF-8");
        return decoded;
    }

    static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65_535) throw new IOException("control string too long");
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    static void requireEmpty(DataInputStream input) throws IOException {
        if (input.available() != 0) throw new IOException("trailing backend control bytes");
    }

    static byte[] singleByteFrame(int type) {
        return new byte[]{(byte) type};
    }
}

package dev.moonbridge.core.session;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/** Minimal blocking RFC 6455 client: masked frames out, the server's binary payloads as one stream in. */
final class TestWebSocketClient implements AutoCloseable {
    static final int TEXT = 0x1, BINARY = 0x2, CLOSE = 0x8, PING = 0x9, PONG = 0xA;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Socket socket;
    private final DataInputStream raw;
    private final OutputStream output;
    private final InputStream payloads = new PayloadStream();
    private byte[] current = new byte[0];
    private int position;
    private int lastControlOpcode = -1;

    private TestWebSocketClient(Socket socket) throws IOException {
        this.socket = socket;
        this.raw = new DataInputStream(socket.getInputStream());
        this.output = socket.getOutputStream();
    }

    /** Sends the upgrade request and returns the HTTP status line the server answered with. */
    static String upgrade(Socket socket, String path, Map<String, String> headers) throws IOException {
        var request = new StringBuilder("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: "
                + Base64.getEncoder().encodeToString(new byte[16]) + "\r\n");
        headers.forEach((name, value) -> request.append(name).append(": ").append(value).append("\r\n"));
        socket.getOutputStream().write(request.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        var response = new ByteArrayOutputStream();
        InputStream input = socket.getInputStream();
        while (true) {
            int next = input.read();
            if (next < 0) break;
            response.write(next);
            byte[] bytes = response.toByteArray();
            int n = bytes.length;
            if (n >= 4 && bytes[n - 4] == '\r' && bytes[n - 3] == '\n' && bytes[n - 2] == '\r' && bytes[n - 1] == '\n') break;
        }
        String text = response.toString(StandardCharsets.US_ASCII);
        return text.isEmpty() ? "" : text.substring(0, text.indexOf("\r\n") < 0 ? text.length() : text.indexOf("\r\n"));
    }

    static TestWebSocketClient connect(Socket socket, String path, Map<String, String> headers) throws IOException {
        String status = upgrade(socket, path, headers);
        if (!status.startsWith("HTTP/1.1 101")) throw new IOException("upgrade refused: " + status);
        return new TestWebSocketClient(socket);
    }

    /** Stream of binary payloads; control frames are recorded, close ends the stream. */
    InputStream input() { return payloads; }

    /** An output stream that sends each write as one binary message. */
    OutputStream output() {
        return new OutputStream() {
            @Override public void write(int value) throws IOException { send(BINARY, new byte[]{(byte) value}); }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                byte[] copy = new byte[length];
                System.arraycopy(bytes, offset, copy, 0, length);
                send(BINARY, copy);
            }
        };
    }

    int lastControlOpcode() { return lastControlOpcode; }

    synchronized void send(int opcode, byte[] payload) throws IOException {
        var frame = new ByteArrayOutputStream();
        frame.write(0x80 | opcode);
        if (payload.length < 126) {
            frame.write(0x80 | payload.length);
        } else if (payload.length < 65536) {
            frame.write(0x80 | 126);
            frame.write(payload.length >>> 8);
            frame.write(payload.length);
        } else {
            frame.write(0x80 | 127);
            for (int shift = 56; shift >= 0; shift -= 8) frame.write((int) ((long) payload.length >>> shift));
        }
        byte[] mask = new byte[4];
        RANDOM.nextBytes(mask);
        frame.write(mask);
        for (int i = 0; i < payload.length; i++) frame.write(payload[i] ^ mask[i & 3]);
        output.write(frame.toByteArray());
        output.flush();
    }

    /** Reads the next server frame; returns false at close or EOF. */
    private boolean nextData() throws IOException {
        while (true) {
            int first;
            try {
                first = raw.readUnsignedByte();
            } catch (EOFException eof) {
                return false;
            }
            int second = raw.readUnsignedByte();
            if ((second & 0x80) != 0) throw new IOException("server frames must not be masked");
            long length = second & 0x7F;
            if (length == 126) length = raw.readUnsignedShort();
            else if (length == 127) length = raw.readLong();
            byte[] payload = new byte[(int) length];
            raw.readFully(payload);
            int opcode = first & 0x0F;
            if (opcode == CLOSE) {
                lastControlOpcode = CLOSE;
                return false;
            }
            if (opcode >= 0x8) {
                lastControlOpcode = opcode;
                continue;
            }
            current = payload;
            position = 0;
            if (payload.length > 0) return true;
        }
    }

    private final class PayloadStream extends InputStream {
        @Override public int read() throws IOException {
            while (position >= current.length) if (!nextData()) return -1;
            return current[position++] & 0xFF;
        }

        /** Returns what one message holds rather than blocking for the full length, like a socket. */
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            while (position >= current.length) if (!nextData()) return -1;
            int count = Math.min(length, current.length - position);
            System.arraycopy(current, position, bytes, offset, count);
            position += count;
            return count;
        }
    }

    @Override public void close() throws IOException { socket.close(); }
}

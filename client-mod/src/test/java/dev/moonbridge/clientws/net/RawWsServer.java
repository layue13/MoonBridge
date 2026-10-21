package dev.moonbridge.clientws.net;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** A byte-level WebSocket peer for tests that need exact control over what the server sends and when. */
final class RawWsServer implements AutoCloseable {

    final ServerSocket server;
    Socket socket;
    DataInputStream in;
    OutputStream out;
    String requestHead;

    RawWsServer() throws IOException {
        server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        server.setSoTimeout(10000);
    }

    int port() {
        return server.getLocalPort();
    }

    String acceptAndReadRequest() throws IOException {
        socket = server.accept();
        socket.setSoTimeout(10000);
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("eof in request head");
            }
            head.write(b);
            matched = (b == (matched % 2 == 0 ? '\r' : '\n')) ? matched + 1 : (b == '\r' ? 1 : 0);
        }
        requestHead = new String(head.toByteArray(), StandardCharsets.US_ASCII);
        return requestHead;
    }

    String header(String name) {
        for (String line : requestHead.split("\r\n")) {
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c)
                .trim()
                .equalsIgnoreCase(name)) {
                return line.substring(c + 1)
                    .trim();
            }
        }
        return null;
    }

    byte[] switchingProtocols() {
        String accept = WebSocketHandshakeHandler.acceptFor(header("Sec-WebSocket-Key"));
        return ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: keep-alive, Upgrade\r\n"
            + "Sec-WebSocket-Accept: "
            + accept
            + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
    }

    /** Unmasked server frame. */
    static byte[] frame(int opcode, boolean fin, byte[] payload) {
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write((fin ? 0x80 : 0) | opcode);
        if (payload.length < 126) {
            f.write(payload.length);
        } else {
            f.write(126);
            f.write(payload.length >>> 8);
            f.write(payload.length & 0xff);
        }
        f.write(payload, 0, payload.length);
        return f.toByteArray();
    }

    void send(byte[]... parts) throws IOException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            all.write(p, 0, p.length);
        }
        out.write(all.toByteArray());
        out.flush();
    }

    /** Reads one client frame, checks it is masked, and returns {opcode, payload...} as {int, byte[]}. */
    Object[] readFrame() throws IOException {
        int b0 = in.readUnsignedByte();
        int b1 = in.readUnsignedByte();
        if ((b1 & 0x80) == 0) {
            throw new IOException("client frame is not masked");
        }
        long len = b1 & 0x7f;
        if (len == 126) {
            len = in.readUnsignedShort();
        } else if (len == 127) {
            len = in.readLong();
        }
        byte[] mask = new byte[4];
        in.readFully(mask);
        byte[] payload = new byte[(int) len];
        in.readFully(payload);
        for (int i = 0; i < payload.length; i++) {
            payload[i] ^= mask[i % 4];
        }
        return new Object[] { b0, payload };
    }

    InputStream input() {
        return in;
    }

    @Override
    public void close() throws IOException {
        if (socket != null) {
            socket.close();
        }
        server.close();
    }
}

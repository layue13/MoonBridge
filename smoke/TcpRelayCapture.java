package dev.strataproxy.smoke;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

/** Disposable loopback relay that captures the first bytes of each backend connection. */
public final class TcpRelayCapture {
    private static final int CAPTURE_LIMIT = 512;
    private static PrintWriter log;

    private TcpRelayCapture() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected listen port, backend port, and log path");
        int listenPort = Integer.parseInt(args[0]);
        int backendPort = Integer.parseInt(args[1]);
        log = new PrintWriter(Files.newBufferedWriter(Path.of(args[2]), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND), true);
        AtomicInteger nextConnection = new AtomicInteger();
        try (ServerSocket listener = new ServerSocket(listenPort, 16, InetAddress.getLoopbackAddress())) {
            System.out.printf("TAP_READY listen=%d backend=%d%n", listenPort, backendPort);
            while (true) {
                Socket client = listener.accept();
                int connection = nextConnection.incrementAndGet();
                try {
                    Socket backend = new Socket(InetAddress.getLoopbackAddress(), backendPort);
                    client.setTcpNoDelay(true);
                    backend.setTcpNoDelay(true);
                    record(connection, "OPEN client=" + client.getRemoteSocketAddress()
                            + " backend=" + backend.getRemoteSocketAddress());
                    Thread upstream = new Thread(() -> copy(connection, "client-to-backend", client, backend),
                            "tap-upstream-" + connection);
                    Thread downstream = new Thread(() -> copy(connection, "backend-to-client", backend, client),
                            "tap-downstream-" + connection);
                    upstream.setDaemon(true);
                    downstream.setDaemon(true);
                    upstream.start();
                    downstream.start();
                } catch (IOException failure) {
                    record(connection, "CONNECT_FAILED " + failure);
                    client.close();
                }
            }
        }
    }

    private static void copy(int connection, String direction, Socket source, Socket target) {
        byte[] buffer = new byte[8192];
        int captured = 0;
        long total = 0;
        try {
            InputStream input = source.getInputStream();
            OutputStream output = target.getOutputStream();
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                int sample = Math.min(read, CAPTURE_LIMIT - captured);
                String hex = sample > 0 ? HexFormat.of().formatHex(buffer, 0, sample) : null;
                output.write(buffer, 0, read);
                output.flush();
                if (hex != null) {
                    record(connection, direction + " forwarded=" + read + " hex=" + hex);
                    captured += sample;
                }
            }
            record(connection, direction + " EOF total=" + total);
        } catch (IOException failure) {
            record(connection, direction + " FAILED total=" + total + " " + failure);
        } finally {
            close(source);
            close(target);
        }
    }

    private static synchronized void record(int connection, String event) {
        log.println(Instant.now() + " connection=" + connection + " " + event);
        log.flush();
    }

    private static void close(Socket socket) {
        try { socket.close(); } catch (IOException ignored) { }
    }
}

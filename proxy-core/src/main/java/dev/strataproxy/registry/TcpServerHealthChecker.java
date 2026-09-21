package dev.strataproxy.registry;

import dev.strataproxy.api.server.ServerHealth;
import dev.strataproxy.api.server.ServerHealthStatus;
import dev.strataproxy.api.server.ServerRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodic backend health checker using either TCP connect or Minecraft status ping probes.
 */
public final class TcpServerHealthChecker implements AutoCloseable {
    private final ServerRegistry registry;
    private final Duration interval;
    private final Duration timeout;
    private final Mode mode;
    private final ScheduledExecutorService scheduler;

    /**
     * Creates a TCP-only health checker.
     *
     * @param registry registry to update with health results
     * @param interval delay between checks
     * @param timeout probe timeout
     */
    public TcpServerHealthChecker(ServerRegistry registry, Duration interval, Duration timeout) {
        this(registry, interval, timeout, "tcp");
    }

    /**
     * Creates a health checker.
     *
     * @param registry registry to update with health results
     * @param interval delay between checks
     * @param timeout probe timeout
     * @param mode {@code tcp} or {@code minecraft-status}
     */
    public TcpServerHealthChecker(ServerRegistry registry, Duration interval, Duration timeout, String mode) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.interval = interval == null ? Duration.ofSeconds(5) : interval;
        this.timeout = timeout == null ? Duration.ofSeconds(2) : timeout;
        this.mode = Mode.from(mode);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "strataproxy-health-check");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Starts background health checks immediately and then at the configured interval.
     */
    public void start() {
        scheduler.execute(this::checkAll);
        scheduler.scheduleWithFixedDelay(this::checkAll, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    void checkAll() {
        for (var server : registry.snapshot()) {
            var descriptor = server.descriptor();
            var started = System.nanoTime();
            try (var socket = new Socket()) {
                var timeoutMillis = Math.toIntExact(timeout.toMillis());
                socket.connect(descriptor.address(), timeoutMillis);
                socket.setSoTimeout(timeoutMillis);
                if (mode == Mode.MINECRAFT_STATUS) {
                    checkMinecraftStatus(socket, descriptor.address());
                }
                var latencyMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                registry.updateHealth(descriptor.name(), ServerHealth.up(latencyMillis));
            } catch (IOException | RuntimeException exception) {
                registry.updateHealth(descriptor.name(), new ServerHealth(
                        ServerHealthStatus.DOWN,
                        -1,
                        1.0d,
                        exception.getClass().getSimpleName(),
                        Instant.now()));
            }
        }
    }

    private static void checkMinecraftStatus(Socket socket, InetSocketAddress address) throws IOException {
        var output = socket.getOutputStream();
        output.write(handshakeFrame(763, statusHost(address), address.getPort()));
        output.write(packetFrame(0));
        output.flush();

        var frame = readFrame(socket.getInputStream(), 256 * 1024);
        var cursor = new Cursor(frame);
        var packetId = cursor.readVarInt();
        if (packetId != 0) {
            throw new IOException("unexpected status packet id: " + packetId);
        }
        var json = cursor.readString();
        if (!json.contains("\"version\"") || !json.contains("\"description\"")) {
            throw new IOException("status response missing required fields");
        }
    }

    private static String statusHost(InetSocketAddress address) {
        var host = address.getHostString();
        if (host != null && !host.isBlank()) {
            return host;
        }
        var inet = address.getAddress();
        return inet == null ? "localhost" : inet.getHostAddress();
    }

    private static byte[] handshakeFrame(int protocolVersion, String host, int port) throws IOException {
        var payload = new java.io.ByteArrayOutputStream();
        writeVarInt(payload, 0);
        writeVarInt(payload, protocolVersion);
        writeString(payload, host);
        payload.write((port >>> 8) & 0xFF);
        payload.write(port & 0xFF);
        writeVarInt(payload, 1);
        return frame(payload.toByteArray());
    }

    private static byte[] packetFrame(int packetId) throws IOException {
        var payload = new java.io.ByteArrayOutputStream();
        writeVarInt(payload, packetId);
        return frame(payload.toByteArray());
    }

    private static byte[] frame(byte[] payload) throws IOException {
        var frame = new java.io.ByteArrayOutputStream();
        writeVarInt(frame, payload.length);
        frame.writeBytes(payload);
        return frame.toByteArray();
    }

    private static byte[] readFrame(InputStream input, int maxFrameBytes) throws IOException {
        var length = readVarInt(input);
        if (length < 0 || length > maxFrameBytes) {
            throw new IOException("status response frame length out of bounds: " + length);
        }
        var body = input.readNBytes(length);
        if (body.length != length) {
            throw new IOException("truncated status response");
        }
        return body;
    }

    private static int readVarInt(InputStream input) throws IOException {
        var value = 0;
        var position = 0;
        while (position < 5) {
            var current = input.read();
            if (current < 0) {
                throw new IOException("truncated VarInt");
            }
            value |= (current & 0x7F) << (position * 7);
            if ((current & 0x80) == 0) {
                return value;
            }
            position++;
        }
        throw new IOException("malformed VarInt");
    }

    private static void writeString(OutputStream output, String value) throws IOException {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length);
        output.write(bytes);
    }

    private static void writeVarInt(OutputStream output, int value) throws IOException {
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

    @Override
    /** Provides close. */
    public void close() {
        scheduler.shutdownNow();
    }

    private enum Mode {
        TCP,
        MINECRAFT_STATUS;

        static Mode from(String value) {
            var normalized = value == null ? "tcp" : value.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "minecraft-status" -> MINECRAFT_STATUS;
                default -> TCP;
            };
        }
    }

    private static final class Cursor {
        private final byte[] bytes;
        private int index;

        private Cursor(byte[] bytes) {
            this.bytes = bytes;
        }

        private int readVarInt() throws IOException {
            var value = 0;
            var position = 0;
            while (position < 5) {
                if (index >= bytes.length) {
                    throw new IOException("truncated VarInt");
                }
                var current = bytes[index++] & 0xFF;
                value |= (current & 0x7F) << (position * 7);
                if ((current & 0x80) == 0) {
                    return value;
                }
                position++;
            }
            throw new IOException("malformed VarInt");
        }

        private String readString() throws IOException {
            var length = readVarInt();
            if (length < 0 || length > bytes.length - index) {
                throw new IOException("string length out of bounds");
            }
            var value = new String(bytes, index, length, StandardCharsets.UTF_8);
            index += length;
            return value;
        }
    }
}

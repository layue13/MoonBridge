package dev.strataproxy.core.session;

import dev.strataproxy.api.PlacementDecision;
import dev.strataproxy.core.backend.BackendId;
import dev.strataproxy.core.backend.BackendOwner;
import dev.strataproxy.core.backend.BackendRegistration;
import dev.strataproxy.core.backend.InMemoryBackendCatalog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Offline protocol-5 PLAY echo benchmark: direct fake backend vs ProxySessionListener. */
public final class ProxySessionBenchmark {
    private static final int IO_TIMEOUT_MILLIS = 30_000;
    private static final int SETUP_TIMEOUT_SECONDS = 20;

    private ProxySessionBenchmark() { }

    public static void main(String[] args) throws Exception {
        int connections = intArg(args, "connections", 4);
        int messages = intArg(args, "messages", 1000);
        int warmup = intArg(args, "warmup", 100);
        int payload = intArg(args, "payload", 1024);
        int repeats = intArg(args, "repeats", 2);
        if (connections < 1 || connections > 32 || messages < 1 || messages > 100_000
                || warmup < 0 || warmup > 10_000 || payload < 1 || payload > 1_048_576
                || repeats < 1 || repeats > 10) {
            throw new IllegalArgumentException("bounds: connections 1..32, messages 1..100000, warmup 0..10000, "
                    + "payload 1..1048576, repeats 1..10");
        }

        byte[] playPayload = new byte[payload];
        playPayload[0] = 0x03; // A bounded, framed PLAY packet body; remaining bytes are deterministic payload.
        for (int i = 1; i < payload; i++) playPayload[i] = (byte) (i * 31 + 7);
        byte[] playFrame = frame(playPayload);

        try (FakeBackend backend = new FakeBackend(connections)) {
            backend.start();
            InMemoryBackendCatalog catalog = new InMemoryBackendCatalog();
            catalog.register(new BackendRegistration(new BackendId("bench"), new BackendOwner("synthetic", 0),
                    URI.create("tcp://127.0.0.1:" + backend.port()), connections, Map.of(), Map.of()));
            ProxySessionListener proxy = new ProxySessionListener(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), catalog);
            proxy.setPlacement(player -> java.util.concurrent.CompletableFuture.completedFuture(
                    Optional.of(PlacementDecision.select("bench"))));
            try {
                int proxyPort = ((InetSocketAddress) proxy.start().toCompletableFuture()
                        .get(SETUP_TIMEOUT_SECONDS, TimeUnit.SECONDS).localAddress()).getPort();
                System.out.printf("Offline synthetic Minecraft 1.7.10 PLAY echo; payload=%d bytes frame=%d bytes "
                                + "connections=%d measured_roundtrips_per_connection=%d warmup_per_connection=%d repeats=%d%n",
                        playPayload.length, playFrame.length, connections, messages, warmup, repeats);
                System.out.println("Direct and proxy phases use the same JVM, fake backend, client code, framed payload, "
                        + "and connection concurrency. Login/setup and warmup are excluded from timing.");
                System.out.println("No Forge, real pack, or real client is involved.");

                boolean proxyFirst = false;
                for (int round = 0; round < repeats; round++) {
                    if (proxyFirst) {
                        runPhase("proxy-" + (round + 1), proxyPort, connections, messages, warmup, playFrame, playPayload);
                        runPhase("direct-" + (round + 1), backend.port(), connections, messages, warmup, playFrame, playPayload);
                    } else {
                        runPhase("direct-" + (round + 1), backend.port(), connections, messages, warmup, playFrame, playPayload);
                        runPhase("proxy-" + (round + 1), proxyPort, connections, messages, warmup, playFrame, playPayload);
                    }
                    proxyFirst = !proxyFirst;
                }
            } finally {
                proxy.close().toCompletableFuture().get(SETUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        }
    }

    private static void runPhase(String name, int port, int connections, int messages, int warmup,
                                 byte[] playFrame, byte[] playPayload) throws Exception {
        List<ClientWorker> clients = new ArrayList<>();
        CountDownLatch prepared = new CountDownLatch(connections);
        CountDownLatch begin = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(connections);
        for (int i = 0; i < connections; i++) {
            ClientWorker client = new ClientWorker(name, i, port, messages, warmup, playFrame,
                    playPayload, prepared, begin, finished);
            clients.add(client);
            Thread thread = new Thread(client, "proxy-session-bench-" + name + "-" + i);
            thread.setDaemon(true);
            thread.start();
        }
        if (!prepared.await(SETUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            begin.countDown();
            throw new IllegalStateException(name + " clients did not finish login/setup before deadline");
        }
        for (ClientWorker client : clients) if (client.failure != null)
            { begin.countDown(); throw new IllegalStateException(name + " client setup failed", client.failure); }

        long started = System.nanoTime();
        begin.countDown();
        if (!finished.await(5, TimeUnit.MINUTES)) throw new IllegalStateException(name + " phase timed out");
        long elapsed = System.nanoTime() - started;
        for (ClientWorker client : clients) if (client.failure != null)
            throw new IllegalStateException(name + " client failed", client.failure);

        long[] samples = clients.stream().flatMapToLong(client -> Arrays.stream(client.latencies)).toArray();
        Arrays.sort(samples);
        long measured = (long) connections * messages;
        double seconds = elapsed / 1_000_000_000.0;
        double oneWayMib = measured * (double) playPayload.length / (1024.0 * 1024.0) / seconds;
        long echoes = clients.stream().mapToLong(client -> client.echoes).sum();
        long correct = clients.stream().mapToLong(client -> client.correct).sum();
        long errors = clients.stream().mapToLong(client -> client.errors).sum();
        if (echoes != measured || correct != measured || errors != 0)
            throw new IllegalStateException(name + " correctness/count mismatch: echoes=" + echoes + " correct="
                    + correct + " errors=" + errors + " expected=" + measured);
        System.out.printf("%s: roundtrips=%d echoes=%d correct=%d errors=%d roundtrips/s=%.1f "
                        + "warmup_roundtrips=%d one_way_MiB/s=%.2f latency_ms_p50=%.3f p95=%.3f p99=%.3f elapsed_ms=%.3f%n",
                name, measured, echoes, correct, errors, measured / seconds, connections * warmup, oneWayMib,
                percentile(samples, .50) / 1e6, percentile(samples, .95) / 1e6,
                percentile(samples, .99) / 1e6, elapsed / 1e6);
    }

    private static final class ClientWorker implements Runnable {
        final String phase;
        final int index, port, messages, warmup;
        final byte[] playFrame;
        final byte[] playPayload;
        final CountDownLatch prepared, begin, finished;
        volatile Exception failure;
        long[] latencies;
        long echoes, correct, errors;

        ClientWorker(String phase, int index, int port, int messages, int warmup, byte[] playFrame,
                     byte[] playPayload, CountDownLatch prepared, CountDownLatch begin, CountDownLatch finished) {
            this.phase = phase; this.index = index; this.port = port; this.messages = messages;
            this.warmup = warmup; this.playFrame = playFrame; this.playPayload = playPayload;
            this.prepared = prepared; this.begin = begin; this.finished = finished;
        }

        @Override public void run() {
            try (Socket socket = new Socket()) {
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(IO_TIMEOUT_MILLIS);
                socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
                DataInputStream input = new DataInputStream(socket.getInputStream());
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                String username = "B" + (phase.startsWith("proxy") ? "P" : "D") + index + "X";
                sendHandshakeAndLogin(output, username);
                readLoginSuccess(input, username);
                if (packetId(readFrame(input)) != 1) throw new IOException("expected Join Game before PLAY");
                if (packetId(readFrame(input)) != 8) throw new IOException("expected Position and Look before PLAY");
                exchange(output, input, warmup);
                echoes = 0; correct = 0; errors = 0; // Warmup is validated, then omitted from reported phase counts.
                latencies = new long[messages];
                prepared.countDown();
                begin.await();
                for (int i = 0; i < messages; i++) {
                    long at = System.nanoTime();
                    output.write(playFrame);
                    output.flush();
                    byte[] echo = readFrame(input);
                    latencies[i] = System.nanoTime() - at;
                    echoes++;
                    if (Arrays.equals(playPayload, echo)) correct++;
                    else { errors++; throw new IOException("echo payload mismatch at roundtrip " + i); }
                }
            } catch (Exception problem) {
                failure = problem;
                if (latencies == null) prepared.countDown();
            } finally { finished.countDown(); }
        }

        private void exchange(DataOutputStream output, DataInputStream input, int count) throws Exception {
            for (int i = 0; i < count; i++) {
                output.write(playFrame);
                output.flush();
                byte[] echo = readFrame(input);
                echoes++;
                if (Arrays.equals(playPayload, echo)) correct++;
                else { errors++; throw new IOException("warmup echo mismatch"); }
            }
        }
    }

    private static final class FakeBackend implements AutoCloseable {
        private final ServerSocket server = new ServerSocket();
        private final ExecutorService clients;
        private final List<Socket> active = java.util.Collections.synchronizedList(new ArrayList<>());
        private volatile boolean closed;
        private Thread acceptThread;

        FakeBackend(int concurrency) throws IOException {
            clients = Executors.newFixedThreadPool(concurrency);
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), concurrency * 2);
        }
        int port() { return server.getLocalPort(); }
        void start() {
            acceptThread = new Thread(() -> {
                while (!closed) {
                    try {
                        Socket socket = server.accept();
                        socket.setTcpNoDelay(true);
                        socket.setSoTimeout(IO_TIMEOUT_MILLIS);
                        active.add(socket);
                        clients.execute(() -> serve(socket));
                    } catch (IOException failure) { if (!closed) close(); }
                }
            }, "synthetic-mc-1710-backend-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        private void serve(Socket socket) {
            try (Socket ignored = socket) {
                DataInputStream input = new DataInputStream(socket.getInputStream());
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                readFrame(input); // Handshake.
                byte[] login = readFrame(input);
                ByteArrayInputStream loginBody = new ByteArrayInputStream(login);
                if (readVarInt(loginBody) != 0) throw new IOException("expected Login Start");
                String username = readString(loginBody, 16);
                UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
                ByteArrayOutputStream success = new ByteArrayOutputStream();
                writeVarInt(success, 2);
                writeString(success, id.toString());
                writeString(success, username);
                writeFrame(output, success.toByteArray());
                writeFrame(output, joinGame());
                writeFrame(output, positionAndLook());
                while (!closed && !socket.isClosed()) {
                    byte[] body = readFrame(input);
                    writeFrame(output, body);
                }
            } catch (EOFException ignored) { }
            catch (IOException ignored) { }
            finally { active.remove(socket); }
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            try { server.close(); } catch (IOException ignored) { }
            synchronized (active) { for (Socket socket : active) try { socket.close(); } catch (IOException ignored) { } }
            clients.shutdownNow();
            try { clients.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (acceptThread != null) try { acceptThread.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private static byte[] joinGame() throws IOException {
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        writeVarInt(packet, 1);
        DataOutputStream out = new DataOutputStream(packet);
        out.writeInt(42); out.writeByte(0); out.writeByte(0); out.writeByte(0); out.writeByte(20);
        writeString(packet, "default");
        return packet.toByteArray();
    }

    private static byte[] positionAndLook() throws IOException {
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        writeVarInt(packet, 8);
        DataOutputStream out = new DataOutputStream(packet);
        out.writeDouble(0.5); out.writeDouble(64.0); out.writeDouble(0.5);
        out.writeFloat(0.0f); out.writeFloat(0.0f); out.writeByte(0);
        return packet.toByteArray();
    }

    private static void sendHandshakeAndLogin(DataOutputStream output, String username) throws IOException {
        ByteArrayOutputStream handshake = new ByteArrayOutputStream();
        writeVarInt(handshake, 0); writeVarInt(handshake, 5); writeString(handshake, "localhost");
        handshake.write(0); handshake.write(255); writeVarInt(handshake, 2);
        writeFrame(output, handshake.toByteArray());
        ByteArrayOutputStream login = new ByteArrayOutputStream();
        writeVarInt(login, 0); writeString(login, username);
        writeFrame(output, login.toByteArray());
    }

    private static void readLoginSuccess(DataInputStream input, String username) throws IOException {
        ByteArrayInputStream success = new ByteArrayInputStream(readFrame(input));
        if (readVarInt(success) != 2) throw new IOException("expected Login Success");
        readString(success, 36);
        if (!username.equals(readString(success, 16))) throw new IOException("Login Success username mismatch");
    }

    private static int packetId(byte[] frame) throws IOException { return readVarInt(new ByteArrayInputStream(frame)); }

    private static byte[] frame(byte[] body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, body.length); out.write(body);
        return out.toByteArray();
    }

    private static byte[] readFrame(DataInputStream input) throws IOException {
        int length = readVarInt(input);
        if (length < 1 || length > 1_048_576) throw new IOException("invalid frame length " + length);
        byte[] body = new byte[length]; input.readFully(body); return body;
    }

    private static void writeFrame(DataOutputStream output, byte[] body) throws IOException {
        writeVarInt(output, body.length); output.write(body); output.flush();
    }

    private static int readVarInt(java.io.InputStream input) throws IOException {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int next = input.read();
            if (next < 0) throw new EOFException();
            result |= (next & 0x7f) << shift;
            if ((next & 0x80) == 0) return result;
        }
        throw new IOException("overlong VarInt");
    }

    private static void writeVarInt(java.io.OutputStream output, int value) throws IOException {
        do {
            int part = value & 0x7f;
            value >>>= 7;
            output.write(value == 0 ? part : part | 0x80);
        } while (value != 0);
    }

    private static String readString(ByteArrayInputStream input, int maxChars) throws IOException {
        int length = readVarInt(input);
        if (length < 0 || length > maxChars * 4) throw new IOException("invalid string length");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException();
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (value.length() > maxChars) throw new IOException("string exceeds character limit");
        return value;
    }

    private static void writeString(ByteArrayOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length); output.write(bytes);
    }

    private static long percentile(long[] sorted, double quantile) {
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(quantile * sorted.length) - 1)];
    }

    private static int intArg(String[] args, String key, int fallback) {
        int found = fallback;
        boolean seen = false;
        List<String> known = List.of("connections", "messages", "warmup", "payload", "repeats");
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--") && !known.contains(args[i].substring(2)))
                throw new IllegalArgumentException("unknown option " + args[i]);
            if (args[i].equals("--" + key)) {
                if (seen || i + 1 >= args.length) throw new IllegalArgumentException("missing or duplicate --" + key);
                found = Integer.parseInt(args[++i]);
                seen = true;
            } else if (args[i].startsWith("--")) {
                if (i + 1 >= args.length) throw new IllegalArgumentException("missing value for " + args[i]);
                i++;
            }
        }
        return found;
    }
}

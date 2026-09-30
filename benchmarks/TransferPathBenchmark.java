package dev.moonbridge.core.session;

import dev.moonbridge.api.PlacementDecision;
import dev.moonbridge.api.PlayerView;
import dev.moonbridge.api.Plugin;
import dev.moonbridge.api.TransferStatus;
import dev.moonbridge.core.backend.BackendId;
import dev.moonbridge.core.backend.BackendOwner;
import dev.moonbridge.core.backend.BackendRegistration;
import dev.moonbridge.core.backend.InMemoryBackendCatalog;
import dev.moonbridge.core.plugin.PluginHost;

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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Controlled end-to-end Minecraft 1.7.10 transfer benchmark for coordination overhead. */
public final class TransferPathBenchmark {
    private static final String LOOPBACK = "127.0.0.2";
    private static final int TIMEOUT_MILLIS = 30_000;
    private static final int SETUP_TIMEOUT_SECONDS = 20;
    private static final int[] CONCURRENCY = {1, 16, 64};
    private static final int ASYNC_DELAY_MILLIS = 4;

    private TransferPathBenchmark() { }

    public static void main(String[] args) throws Exception {
        int repeats = intArg(args, "repeats", 5);
        boolean onlyNoSubscriber = hasFlag(args, "--only-no-subscriber");
        if (repeats < 1 || repeats > 10) throw new IllegalArgumentException("repeats must be 1..10");
        System.out.println("Offline Minecraft 1.7.10 transfer path; fake loopback backends; protocol setup excluded.");
        if (onlyNoSubscriber) {
            System.out.println("Scenario filter: host with no subscribers only.");
        } else {
            System.out.println("Scenarios: host with no subscribers; 1/4 allow-only immediate; 1/4 release-source immediate; "
                    + "1/4 release-source with 4ms asynchronous prepare and release callbacks.");
        }
        if (onlyNoSubscriber) {
            System.out.println("No TransferPreparingEvent listener is installed. Concurrency=1/16/64; "
                    + "reports whole transfer NETWORK_READY latency and completed transfers/s.");
        } else {
            System.out.println("Each case uses the real PluginHost/AsyncEventDispatcher event path. Concurrency=1/16/64; "
                    + "reports whole transfer NETWORK_READY latency and completed transfers/s.");
        }
        System.out.println("No Forge, real backend, real client, persistence plugin, or production workload is involved.");

        for (int concurrency : CONCURRENCY) {
            runScenario(concurrency, 0, false, 0, repeats);
            if (onlyNoSubscriber) continue;
            for (int participants : new int[]{1, 4}) {
                runScenario(concurrency, participants, false, 0, repeats);
            }
            for (int participants : new int[]{1, 4}) {
                runScenario(concurrency, participants, true, 0, repeats);
                runScenario(concurrency, participants, true, ASYNC_DELAY_MILLIS, repeats);
            }
        }
    }

    private static void runScenario(int concurrency, int participants, boolean releaseSource,
                                    int delayMillis, int repeats) throws Exception {
        String mode = participants == 0 ? "no-subscriber"
                : (releaseSource ? "release" : "allow") + "-p" + participants + "-delay" + delayMillis + "ms";
        int measuredBatches = Math.max(repeats, (100 + concurrency - 1) / concurrency);
        int warmupBatches = 3;
        List<Long> latencyNanos = new ArrayList<>(concurrency * measuredBatches);
        long totalElapsedNanos = 0;
        int successes = 0;

        try (FakeBackend source = new FakeBackend(concurrency);
             FakeBackend targetA = new FakeBackend(concurrency);
             FakeBackend targetB = new FakeBackend(concurrency)) {
            source.start(); targetA.start(); targetB.start();
            InMemoryBackendCatalog catalog = new InMemoryBackendCatalog();
            register(catalog, "source", source.port());
            register(catalog, "target-a", targetA.port());
            register(catalog, "target-b", targetB.port());
            ProxySessionListener proxy = new ProxySessionListener(
                    new InetSocketAddress(InetAddress.getByName(LOOPBACK), 0), catalog);
            proxy.setPlacement(player -> CompletableFuture.completedFuture(
                    java.util.Optional.of(PlacementDecision.select("source"))));
            PluginHost host = new PluginHost(catalog, proxy, Duration.ofSeconds(10), Duration.ofSeconds(10));
            try {
                List<Plugin> plugins = participants == 0 ? List.of() : List.of(createParticipantPlugin(
                        participants, releaseSource, delayMillis));
                host.load(plugins);
                host.enable();
                proxy.setEvents(host, Duration.ofSeconds(10));
                int proxyPort = ((InetSocketAddress) proxy.start().toCompletableFuture()
                        .get(SETUP_TIMEOUT_SECONDS, TimeUnit.SECONDS).localAddress()).getPort();
                ExecutorService clients = Executors.newFixedThreadPool(concurrency);
                List<TransferWorker> workers = new ArrayList<>(concurrency);
                try {
                    for (int i = 0; i < concurrency; i++) {
                        TransferWorker worker = new TransferWorker(proxyPort, proxy, username(0, i));
                        worker.open();
                        workers.add(worker);
                    }
                    for (int repeat = 0; repeat < warmupBatches + measuredBatches; repeat++) {
                        CountDownLatch begin = new CountDownLatch(1);
                        CountDownLatch finished = new CountDownLatch(concurrency);
                        AtomicReference<Throwable> failure = new AtomicReference<>();
                        for (TransferWorker worker : workers) {
                            String target = worker.nextTarget();
                            clients.execute(() -> {
                                try {
                                    if (!begin.await(SETUP_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                                        throw new IOException("start gate timed out");
                                    worker.transfer(target);
                                } catch (Throwable problem) {
                                    failure.compareAndSet(null, problem);
                                } finally {
                                    finished.countDown();
                                }
                            });
                        }
                        long batchStart = System.nanoTime();
                        begin.countDown();
                        if (!finished.await(SETUP_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                            throw new IOException("transfers timed out at concurrency " + concurrency);
                        long batchElapsed = System.nanoTime() - batchStart;
                        Throwable problem = failure.get();
                        if (problem != null) throw new IOException("transfer worker failed", problem);
                        if (repeat < warmupBatches) continue;
                        totalElapsedNanos += batchElapsed;
                        for (TransferWorker worker : workers) {
                            latencyNanos.add(worker.latencyNanos);
                            successes++;
                        }
                    }
                } finally {
                    clients.shutdownNow();
                    clients.awaitTermination(5, TimeUnit.SECONDS);
                    for (TransferWorker worker : workers) worker.close();
                }
            } finally {
                try { proxy.close().toCompletableFuture().get(SETUP_TIMEOUT_SECONDS, TimeUnit.SECONDS); }
                finally { host.close(); }
            }
        }

        long[] sorted = latencyNanos.stream().mapToLong(Long::longValue).sorted().toArray();
        double throughput = successes * 1_000_000_000.0 / totalElapsedNanos;
        System.out.printf("mode=%s concurrency=%d warmup_batches=%d measured_batches=%d samples=%d "
                        + "success=%d transfers/s=%.1f "
                        + "latency_ms_p50=%.3f p95=%.3f p99=%.3f batch_elapsed_ms_total=%.1f%n",
                mode, concurrency, warmupBatches, measuredBatches, sorted.length, successes, throughput,
                millis(percentile(sorted, .50)),
                millis(percentile(sorted, .95)), millis(percentile(sorted, .99)),
                totalElapsedNanos / 1_000_000.0);
    }

    private static Plugin createParticipantPlugin(int participants, boolean releaseSource, int delayMillis)
            throws ReflectiveOperationException {
        Class<?> type = Class.forName("dev.moonbridge.core.session.TransferParticipantPlugin");
        return (Plugin) type.getDeclaredConstructor(int.class, boolean.class, int.class)
                .newInstance(participants, releaseSource, delayMillis);
    }

    private static void register(InMemoryBackendCatalog catalog, String name, int port) {
        catalog.register(new BackendRegistration(new BackendId(name), new BackendOwner("benchmark", 0),
                URI.create("tcp://" + LOOPBACK + ":" + port), Map.of(), Map.of()));
    }

    private static String username(int repeat, int index) {
        return String.format("T%02dP%02dX", repeat, index);
    }

    private static double millis(long nanos) { return nanos / 1_000_000.0; }

    private static long percentile(long[] sorted, double quantile) {
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(quantile * sorted.length) - 1)];
    }

    private static final class TransferWorker implements AutoCloseable {
        private final int proxyPort;
        private final ProxySessionListener proxy;
        private final String username;
        private Socket socket;
        private DataInputStream input;
        private DataOutputStream output;
        private PlayerView player;
        private int transferOrdinal;
        private long latencyNanos;

        private TransferWorker(int proxyPort, ProxySessionListener proxy, String username) {
            this.proxyPort = proxyPort; this.proxy = proxy; this.username = username;
        }

        private void open() throws Exception {
            socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(TIMEOUT_MILLIS);
            socket.connect(new InetSocketAddress(InetAddress.getByName(LOOPBACK), proxyPort), 5000);
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(socket.getOutputStream());
            sendHandshakeAndLogin(output, username);
            if (packetId(readFrame(input)) != 2 || packetId(readFrame(input)) != 1
                    || packetId(readFrame(input)) != 8) throw new IOException("initial backend setup mismatch");
            player = awaitPlayer(proxy, username);
        }

        private String nextTarget() {
            return (transferOrdinal++ & 1) == 0 ? "target-a" : "target-b";
        }

        private void transfer(String target) throws Exception {
            long start = System.nanoTime();
            var result = proxy.transfer(player.identity(), target).toCompletableFuture()
                    .get(SETUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            latencyNanos = System.nanoTime() - start;
            if (result.status() != TransferStatus.NETWORK_READY)
                throw new IOException("transfer returned " + result.status() + " "
                        + result.detail().orElse(""));
            int first = packetId(readFrame(input));
            int second = packetId(readFrame(input));
            int third = packetId(readFrame(input));
            if (first != 7 || second != 7 || third != 8)
                throw new IOException("unexpected target transition packet IDs " + first + "," + second + "," + third);
        }

        @Override public void close() throws IOException { if (socket != null) socket.close(); }
    }

    private static PlayerView awaitPlayer(ProxySessionListener proxy, String username) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SETUP_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            for (PlayerView player : proxy.online()) if (player.username().equals(username)) return player;
            Thread.sleep(2);
        }
        throw new IOException("proxy did not publish " + username);
    }

    private static final class FakeBackend implements AutoCloseable {
        private final ServerSocket server = new ServerSocket();
        private final ExecutorService clients;
        private final List<Socket> active = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean closed;
        private Thread acceptThread;

        private FakeBackend(int concurrency) throws IOException {
            clients = Executors.newFixedThreadPool(concurrency);
            server.bind(new InetSocketAddress(InetAddress.getByName(LOOPBACK), 0), concurrency * 2);
        }
        private int port() { return server.getLocalPort(); }
        private void start() {
            acceptThread = new Thread(() -> {
                while (!closed) {
                    try {
                        Socket socket = server.accept();
                        socket.setTcpNoDelay(true); socket.setSoTimeout(TIMEOUT_MILLIS); active.add(socket);
                        clients.execute(() -> serve(socket));
                    } catch (IOException problem) { if (!closed) close(); }
                }
            }, "transfer-benchmark-backend-accept");
            acceptThread.setDaemon(true); acceptThread.start();
        }
        private void serve(Socket socket) {
            try (Socket ignored = socket) {
                DataInputStream input = new DataInputStream(socket.getInputStream());
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                readFrame(input);
                byte[] login = readFrame(input);
                ByteArrayInputStream loginData = new ByteArrayInputStream(login);
                if (readVarInt(loginData) != 0) throw new IOException("expected Login Start");
                String username = readString(loginData, 16);
                UUID playerId = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
                ByteArrayOutputStream success = new ByteArrayOutputStream();
                writeVarInt(success, 2); writeString(success, playerId.toString()); writeString(success, username);
                writeFrame(output, success.toByteArray());
                writeFrame(output, joinGame(99));
                writeFrame(output, positionAndLook());
                while (!closed && !socket.isClosed()) readFrame(input);
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

    private static byte[] joinGame(int entityId) throws IOException {
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        writeVarInt(packet, 1);
        DataOutputStream out = new DataOutputStream(packet);
        out.writeInt(entityId); out.writeByte(0); out.writeByte(0); out.writeByte(0); out.writeByte(20);
        writeString(packet, "default");
        return packet.toByteArray();
    }

    private static byte[] positionAndLook() throws IOException {
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        writeVarInt(packet, 8);
        DataOutputStream out = new DataOutputStream(packet);
        out.writeDouble(.5); out.writeDouble(64); out.writeDouble(.5); out.writeFloat(0); out.writeFloat(0); out.writeByte(0);
        return packet.toByteArray();
    }

    private static void sendHandshakeAndLogin(DataOutputStream output, String username) throws IOException {
        ByteArrayOutputStream handshake = new ByteArrayOutputStream();
        writeVarInt(handshake, 0); writeVarInt(handshake, 5); writeString(handshake, "localhost");
        handshake.write(0); handshake.write(255); writeVarInt(handshake, 2); writeFrame(output, handshake.toByteArray());
        ByteArrayOutputStream login = new ByteArrayOutputStream();
        writeVarInt(login, 0); writeString(login, username); writeFrame(output, login.toByteArray());
    }

    private static int packetId(byte[] frame) throws IOException { return readVarInt(new ByteArrayInputStream(frame)); }

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
            int part = value & 0x7f; value >>>= 7;
            output.write(value == 0 ? part : part | 0x80);
        } while (value != 0);
    }

    private static String readString(ByteArrayInputStream input, int maxChars) throws IOException {
        int length = readVarInt(input);
        if (length < 0 || length > maxChars * 4) throw new IOException("invalid string length");
        byte[] encoded = input.readNBytes(length);
        if (encoded.length != length) throw new EOFException();
        return new String(encoded, StandardCharsets.UTF_8);
    }

    private static void writeString(ByteArrayOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, encoded.length); output.write(encoded);
    }

    private static int intArg(String[] args, String name, int fallback) {
        int value = fallback;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--" + name)) {
                if (++i >= args.length) throw new IllegalArgumentException("missing value for --" + name);
                value = Integer.parseInt(args[i]);
            } else if (args[i].equals("--only-no-subscriber")) {
                continue;
            } else if (args[i].startsWith("--")) {
                throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        return value;
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String arg : args) if (arg.equals(flag)) return true;
        return false;
    }
}

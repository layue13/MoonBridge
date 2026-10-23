package dev.moonbridge.core.session;

import dev.moonbridge.api.PlacementDecision;
import dev.moonbridge.api.PlayerView;
import dev.moonbridge.api.TransferStatus;
import dev.moonbridge.core.backend.BackendId;
import dev.moonbridge.core.backend.BackendOwner;
import dev.moonbridge.core.backend.BackendRegistration;
import dev.moonbridge.core.backend.InMemoryBackendCatalog;

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
    private static final String WEBSOCKET_PATH = "/mc";

    private ProxySessionBenchmark() { }

    public static void main(String[] args) throws Exception {
        int connections = intArg(args, "connections", 4);
        int messages = intArg(args, "messages", 1000);
        int warmup = intArg(args, "warmup", 100);
        int payload = intArg(args, "payload", 1024);
        int repeats = intArg(args, "repeats", 2);
        int window = intArg(args, "window", 1);
        int commandInterceptor = intArg(args, "command-interceptor", 1);
        int burst = intArg(args, "burst", 1);
        int webSocket = intArg(args, "websocket", 0);
        int webSocketTls = intArg(args, "websocket-tls", 0);
        int clientCoalesce = intArg(args, "client-coalesce", 0);
        String profile = stringArg(args, "profile", "echo");
        String mode = modeArg(args);
        var transport = dev.moonbridge.core.session.channel.NetworkTransport.select(
                dev.moonbridge.core.session.channel.NetworkTransport.Preference.valueOf(
                        stringArg(args, "transport", "auto").toUpperCase(java.util.Locale.ROOT)));
        boolean afterTransfer = mode.equals("post-transfer");
        if (connections < 1 || connections > 32 || messages < 1 || messages > 100_000
                || warmup < 0 || warmup > 10_000 || payload < 5 || payload > 1_048_576
                || repeats < 1 || repeats > 10 || window < 1 || window > 1024
                || (commandInterceptor != 0 && commandInterceptor != 1) || burst < 1 || burst > 256
                || (webSocket != 0 && webSocket != 1) || (webSocketTls != 0 && webSocketTls != 1)
                || (clientCoalesce != 0 && clientCoalesce != 1)) {
            throw new IllegalArgumentException("bounds: connections 1..32, messages 1..100000, warmup 0..10000, "
                    + "payload 5..1048576, repeats 1..10, window 1..1024, burst 1..256");
        }
        if (!profile.equals("echo") && !profile.equals("modded"))
            throw new IllegalArgumentException("profile must be echo or modded");
        if (webSocketTls == 1 && webSocket == 0) throw new IllegalArgumentException("--websocket-tls needs --websocket 1");
        ModdedTraffic modded = profile.equals("modded") ? new ModdedTraffic() : null;
        if (modded != null && burst != 1) throw new IllegalArgumentException("--profile modded sets its own frame mix; "
                + "--burst does not apply");
        if (modded == null && clientCoalesce == 1)
            throw new IllegalArgumentException("--client-coalesce applies to --profile modded");

        byte[] playPayload = new byte[payload];
        playPayload[0] = 0x03; // PLAY packet ID; bytes 1..4 carry the per-request sequence.
        for (int i = 1; i < payload; i++) playPayload[i] = (byte) (i * 31 + 7);
        byte[] playFrame = frame(playPayload);
        BenchmarkTls tls = webSocketTls == 1 ? BenchmarkTls.create() : null;
        ClientOptions options = new ClientOptions(webSocket == 1, tls == null ? null : tls.client(), modded,
                clientCoalesce == 1);

        try (FakeBackend backend = new FakeBackend(connections, 42, burst, modded);
             FakeBackend replacement = afterTransfer ? new FakeBackend(connections, 99, burst, modded) : null) {
            backend.start();
            if (replacement != null) replacement.start();
            InMemoryBackendCatalog catalog = new InMemoryBackendCatalog();
            catalog.register(new BackendRegistration(new BackendId("bench"), new BackendOwner("synthetic", 0),
                    URI.create("tcp://127.0.0.1:" + backend.port()), Map.of(), Map.of()));
            if (replacement != null) {
                catalog.register(new BackendRegistration(new BackendId("replacement"), new BackendOwner("synthetic", 0),
                        URI.create("tcp://127.0.0.1:" + replacement.port()), Map.of(), Map.of()));
            }
            ProxySessionListener proxy = new ProxySessionListener(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), catalog, null,
                    Duration.ofSeconds(15), transport);
            proxy.setPlacement(player -> java.util.concurrent.CompletableFuture.completedFuture(
                    Optional.of(PlacementDecision.select("bench"))));
            if (commandInterceptor == 1) proxy.setCommandDispatcher((player, message, reply, admission) -> false);
            if (webSocket == 1) {
                proxy.setWebSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                        new dev.moonbridge.core.websocket.WebSocketFrontend.Settings(WEBSOCKET_PATH,
                                tls == null ? null : tls.server(),
                                dev.moonbridge.core.websocket.ClientAddressPolicy.direct(), Duration.ofSeconds(10)));
            }
            try {
                int tcpPort = ((InetSocketAddress) proxy.start().toCompletableFuture()
                        .get(SETUP_TIMEOUT_SECONDS, TimeUnit.SECONDS).localAddress()).getPort();
                // Proxy clients use the WebSocket listener when it is enabled; direct clients always use plain TCP,
                // because the fake backend is a Minecraft TCP server.
                int proxyPort = webSocket == 1
                        ? ((InetSocketAddress) proxy.webSocketChannel().localAddress()).getPort() : tcpPort;
                System.out.printf("Offline synthetic Minecraft 1.7.10 PLAY echo; mode=%s profile=%s payload=%d bytes "
                                + "frame=%d bytes connections=%d measured_roundtrips_per_connection=%d "
                                + "warmup_per_connection=%d window_per_connection=%d repeats=%d command_interceptor=%d "
                                + "burst=%d transport=%s proxy_frontend=%s client_coalesce=%d%n",
                        mode, profile, playPayload.length, playFrame.length, connections, messages, warmup, window,
                        repeats, commandInterceptor, burst, transport.name(),
                        webSocket == 0 ? "tcp" : tls == null ? "websocket" : "websocket-tls", clientCoalesce);
                if (webSocket == 1) System.out.println("Proxy clients connect over " + (tls == null ? "plain ws://"
                        : "wss:// (JDK TLS client, " + tls.description() + ")") + " and send one masked binary "
                        + "message per client flush; direct clients use TCP to the same fake backend.");
                if (burst > 1) System.out.println("Each roundtrip writes " + burst + " frames in one client write; "
                        + "the fake backend echoes them with one flush. Window counts bursts.");
                if (modded != null) System.out.println(modded.description(clientCoalesce == 1));
                System.out.println("Direct and proxy phases use the same JVM, fake backend, client code, framed payload, "
                        + "connection concurrency, and per-client in-flight window. Login/setup and warmup are excluded from timing.");
                if (afterTransfer) System.out.println("Proxy clients switch from bench to replacement before warmup; "
                        + "direct clients connect to replacement. Transfer is excluded from timing.");
                System.out.println("No Forge, real pack, or real client is involved.");

                int directPort = replacement == null ? backend.port() : replacement.port();
                boolean proxyFirst = false;
                for (int round = 0; round < repeats; round++) {
                    if (proxyFirst) {
                        runPhase("proxy-" + (round + 1), proxyPort, connections, messages, warmup, window,
                                playFrame, playPayload, proxy, afterTransfer, burst, options);
                        runPhase("direct-" + (round + 1), directPort, connections, messages, warmup, window,
                                playFrame, playPayload, proxy, afterTransfer, burst, options);
                    } else {
                        runPhase("direct-" + (round + 1), directPort, connections, messages, warmup, window,
                                playFrame, playPayload, proxy, afterTransfer, burst, options);
                        runPhase("proxy-" + (round + 1), proxyPort, connections, messages, warmup, window,
                                playFrame, playPayload, proxy, afterTransfer, burst, options);
                    }
                    proxyFirst = !proxyFirst;
                }
            } finally {
                proxy.close().toCompletableFuture().get(SETUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        }
    }

    private static void runPhase(String name, int port, int connections, int messages, int warmup, int window,
                                 byte[] playFrame, byte[] playPayload, ProxySessionListener proxy,
                                 boolean afterTransfer, int burst, ClientOptions options) throws Exception {
        List<ClientWorker> clients = new ArrayList<>();
        CountDownLatch prepared = new CountDownLatch(connections);
        CountDownLatch begin = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(connections);
        for (int i = 0; i < connections; i++) {
            ClientWorker client = new ClientWorker(name, i, port, messages, warmup, window, playFrame,
                    playPayload, prepared, begin, finished, clients, proxy, afterTransfer, burst,
                    options.forPhase(name.startsWith("proxy")));
            clients.add(client);
        }
        for (ClientWorker client : clients) {
            Thread thread = new Thread(client, "proxy-session-bench-" + name + "-" + client.index);
            thread.setDaemon(true);
            thread.start();
        }
        if (!prepared.await(SETUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            begin.countDown();
            closeClients(clients);
            throw new IllegalStateException(name + " clients did not finish login/setup before deadline");
        }
        for (ClientWorker client : clients) if (client.failure != null)
            { begin.countDown(); closeClients(clients); throw new IllegalStateException(name + " client setup failed", client.failure); }

        long ioCpuStart = proxyIoCpuNanos();
        long segmentsStart = tcpOutSegments();
        long started = System.nanoTime();
        begin.countDown();
        if (!finished.await(5, TimeUnit.MINUTES)) {
            closeClients(clients);
            throw new IllegalStateException(name + " phase timed out");
        }
        long elapsed = System.nanoTime() - started;
        long ioCpu = proxyIoCpuNanos() - ioCpuStart;
        long segments = segmentsStart < 0 ? -1 : tcpOutSegments() - segmentsStart;
        for (ClientWorker client : clients) if (client.failure != null) {
            closeClients(clients);
            throw new IllegalStateException(name + " client failed", client.failure);
        }

        long[] samples = clients.stream().flatMapToLong(client -> Arrays.stream(client.latencies)).toArray();
        Arrays.sort(samples);
        long measured = (long) connections * messages;
        double seconds = elapsed / 1_000_000_000.0;
        boolean modded = options.modded() != null;
        // The modded profile counts frames and bytes in both directions; the echo profile counts one direction.
        long frames = modded ? clients.stream().mapToLong(client -> client.frames).sum() : measured * burst;
        double oneWayMib = modded
                ? clients.stream().mapToLong(client -> client.serverBytes).sum() / (1024.0 * 1024.0) / seconds
                : frames * (double) playPayload.length / (1024.0 * 1024.0) / seconds;
        long echoes = clients.stream().mapToLong(client -> client.echoes).sum();
        long correct = clients.stream().mapToLong(client -> client.correct).sum();
        long errors = clients.stream().mapToLong(client -> client.errors).sum();
        if (echoes != measured || correct != measured || errors != 0)
            throw new IllegalStateException(name + " correctness/count mismatch: echoes=" + echoes + " correct="
                    + correct + " errors=" + errors + " expected=" + measured);
        System.out.printf("%s: roundtrips=%d echoes=%d correct=%d errors=%d roundtrips/s=%.1f "
                        + "warmup_roundtrips=%d window=%d one_way_MiB/s=%.2f latency_ms_p50=%.3f p95=%.3f "
                        + "p99=%.3f elapsed_ms=%.3f frames/s=%.1f proxy_io_cpu_ns/frame=%.0f tcp_out_segments/frame=%s%n",
                name, measured, echoes, correct, errors, measured / seconds, connections * warmup, window, oneWayMib,
                percentile(samples, .50) / 1e6, percentile(samples, .95) / 1e6,
                percentile(samples, .99) / 1e6, elapsed / 1e6, frames / seconds, ioCpu / (double) frames,
                segments < 0 ? "n/a" : String.format("%.3f", segments / (double) frames));
        if (modded) {
            long[] bursts = clients.stream().mapToLong(client -> client.loginBurstNanos).sorted().toArray();
            System.out.printf("%s: modded server_to_client_MiB/s=%.2f login_burst_ms_p50=%.3f max=%.3f%n", name,
                    oneWayMib, percentile(bursts, .50) / 1e6, bursts[bursts.length - 1] / 1e6);
        }
    }

    private static void closeClients(List<ClientWorker> clients) {
        for (ClientWorker client : clients) client.closeSocket();
    }

    private static final class ClientWorker implements Runnable {
        final String phase;
        final int index, port, messages, warmup, window;
        final byte[] playFrame;
        final byte[] playPayload;
        final CountDownLatch prepared, begin, finished;
        final List<ClientWorker> peers;
        final ProxySessionListener proxy;
        final boolean afterTransfer;
        final int burst;
        final ClientOptions options;
        final ModdedTraffic modded;
        volatile Exception failure;
        volatile Socket socket;
        long[] latencies;
        long echoes, correct, errors;
        long frames, serverBytes, loginBurstNanos;

        ClientWorker(String phase, int index, int port, int messages, int warmup, int window, byte[] playFrame,
                     byte[] playPayload, CountDownLatch prepared, CountDownLatch begin, CountDownLatch finished,
                     List<ClientWorker> peers, ProxySessionListener proxy, boolean afterTransfer, int burst,
                     ClientOptions options) {
            this.phase = phase; this.index = index; this.port = port; this.messages = messages;
            this.warmup = warmup; this.window = window; this.playFrame = playFrame.clone(); this.playPayload = playPayload;
            this.prepared = prepared; this.begin = begin; this.finished = finished; this.peers = peers;
            this.proxy = proxy; this.afterTransfer = afterTransfer; this.burst = burst; this.options = options;
            this.modded = options.modded();
        }

        @Override public void run() {
            try (Socket plain = new Socket()) {
                this.socket = plain;
                plain.setTcpNoDelay(true);
                plain.setSoTimeout(IO_TIMEOUT_MILLIS);
                plain.connect(new InetSocketAddress("127.0.0.1", port), 5000);
                Socket socket = plain;
                if (options.tls() != null) {
                    var secure = (javax.net.ssl.SSLSocket) options.tls().getSocketFactory()
                            .createSocket(plain, "localhost", port, true);
                    secure.startHandshake();
                    this.socket = socket = secure;
                }
                DataInputStream input;
                DataOutputStream output;
                if (options.webSocket()) {
                    var streams = new WebSocketStreams(socket, port);
                    input = new DataInputStream(streams.input);
                    output = new DataOutputStream(streams.output);
                } else {
                    // The modded profile reads and writes packet by packet; buffering gives TCP the same read cost
                    // as the WebSocket client's buffered input and the same write boundaries as its messages.
                    input = new DataInputStream(modded == null ? socket.getInputStream()
                            : new java.io.BufferedInputStream(socket.getInputStream(), 64 * 1024));
                    output = new DataOutputStream(modded == null ? socket.getOutputStream()
                            : new java.io.BufferedOutputStream(socket.getOutputStream(), 64 * 1024));
                }
                String username = "B" + (phase.startsWith("proxy") ? "P" : "D") + index + "X";
                sendHandshakeAndLogin(output, username);
                readLoginSuccess(input, username);
                if (packetId(readFrame(input)) != 1) throw new IOException("expected Join Game before PLAY");
                if (packetId(readFrame(input)) != 8) throw new IOException("expected Position and Look before PLAY");
                if (modded != null) readLoginBurst(input);
                if (afterTransfer && phase.startsWith("proxy")) {
                    PlayerView player = awaitPlayer(proxy, username);
                    var transfer = proxy.transfer(player.identity(), "replacement").toCompletableFuture();
                    if (packetId(readFrame(input)) != 7 || packetId(readFrame(input)) != 7)
                        throw new IOException("expected two Respawns from replacement backend");
                    if (packetId(readFrame(input)) != 8)
                        throw new IOException("expected Position and Look from replacement backend");
                    if (modded != null) readLoginBurst(input);
                    var result = transfer.get(SETUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    if (result.status() != TransferStatus.NETWORK_READY)
                        throw new IOException("replacement transfer failed: " + result.status()
                                + " " + result.detail().orElse(""));
                    if (!proxy.find(player.identity()).flatMap(PlayerView::currentServer).orElse("")
                            .equals("replacement")) throw new IOException("replacement player view missing");
                }
                if (modded != null) exchangeModded(output, input, warmup, null, 0);
                else exchange(output, input, warmup, null, 0);
                // Warmup is validated, then omitted from reported phase counts.
                echoes = 0; correct = 0; errors = 0; frames = 0; serverBytes = 0;
                latencies = new long[messages];
                prepared.countDown();
                begin.await();
                if (modded != null) exchangeModded(output, input, messages, latencies, warmup);
                else exchange(output, input, messages, latencies, warmup);
            } catch (Exception problem) {
                failure = problem;
                if (latencies == null) prepared.countDown();
                closeClients(peers);
            } finally { finished.countDown(); }
        }

        private void exchange(DataOutputStream output, DataInputStream input, int count, long[] samples,
                              int firstSequence) throws Exception {
            long[] outstandingSince = new long[Math.min(window, Math.max(1, count))];
            byte[] burstFrames = burst == 1 ? playFrame : new byte[playFrame.length * burst];
            int sent = 0;
            int received = 0;
            while (received < count) {
                while (sent < count && sent - received < outstandingSince.length) {
                    int sequence = firstSequence + sent;
                    if (burst == 1) writeSequence(playFrame, playFrame.length - playPayload.length, sequence);
                    else for (int k = 0; k < burst; k++) {
                        writeSequence(playFrame, playFrame.length - playPayload.length, sequence * burst + k);
                        System.arraycopy(playFrame, 0, burstFrames, k * playFrame.length, playFrame.length);
                    }
                    outstandingSince[sent % outstandingSince.length] = System.nanoTime();
                    output.write(burstFrames);
                    output.flush();
                    sent++;
                }
                for (int k = 0; k < burst; k++) {
                    byte[] echo = readFrame(input);
                    if (k == burst - 1 && samples != null) {
                        samples[received] = System.nanoTime() - outstandingSince[received % outstandingSince.length];
                    }
                    int expectedSequence = burst == 1 ? firstSequence + received
                            : (firstSequence + received) * burst + k;
                    try {
                        verifyEcho(playPayload, echo, expectedSequence);
                    } catch (IOException mismatch) {
                        errors++;
                        throw new IOException("echo validation failed at roundtrip " + received + ": "
                                + mismatch.getMessage(), mismatch);
                    }
                }
                echoes++;
                correct++;
                received++;
            }
        }

        /** The backend's registry-sync stand-in after each Position and Look; its wall time is reported, not timed. */
        private void readLoginBurst(DataInputStream input) throws IOException {
            long started = System.nanoTime();
            ModdedTraffic.Packet[] login = modded.login;
            for (int i = 0; i < login.length; i++) modded.verify(login[i], readFrame(input), i);
            loginBurstNanos = System.nanoTime() - started;
        }

        /**
         * One roundtrip is one client tick: three small serverbound packets, answered by the backend with that
         * tick's clientbound mix. Without client coalescing each packet is its own write and flush, as the released
         * client mod sends one WebSocket message per vanilla packet.
         */
        private void exchangeModded(DataOutputStream output, DataInputStream input, int count, long[] samples,
                                    int firstSequence) throws Exception {
            long[] outstandingSince = new long[Math.min(window, Math.max(1, count))];
            int sent = 0;
            int received = 0;
            while (received < count) {
                while (sent < count && sent - received < outstandingSince.length) {
                    int sequence = firstSequence + sent;
                    outstandingSince[sent % outstandingSince.length] = System.nanoTime();
                    for (ModdedTraffic.Packet packet : modded.client(sequence)) {
                        output.write(modded.frame(packet, sequence));
                        if (!options.coalesce()) output.flush();
                        frames++;
                    }
                    if (options.coalesce()) output.flush();
                    sent++;
                }
                int sequence = firstSequence + received;
                for (ModdedTraffic.Packet packet : modded.server(sequence)) {
                    byte[] body = readFrame(input);
                    try {
                        modded.verify(packet, body, sequence);
                    } catch (IOException mismatch) {
                        errors++;
                        throw new IOException("tick " + received + ": " + mismatch.getMessage(), mismatch);
                    }
                    frames++;
                    serverBytes += body.length;
                }
                if (samples != null) {
                    samples[received] = System.nanoTime() - outstandingSince[received % outstandingSince.length];
                }
                echoes++;
                correct++;
                received++;
            }
        }

        void closeSocket() {
            Socket current = socket;
            if (current != null) try { current.close(); } catch (IOException ignored) { }
        }
    }

    /**
     * A minimal RFC 6455 client over a blocking socket, enough for the benchmark: the output buffers writes and sends
     * them as one masked binary message per flush, as the client mod sends one message per vanilla write; the input
     * yields the payload of the server's binary messages as one continuous byte stream.
     */
    private static final class WebSocketStreams {
        final java.io.OutputStream output;
        final java.io.InputStream input;

        WebSocketStreams(Socket socket, int port) throws IOException {
            var raw = new java.io.BufferedOutputStream(socket.getOutputStream(), 64 * 1024);
            var in = new java.io.BufferedInputStream(socket.getInputStream(), 64 * 1024);
            byte[] key = new byte[16];
            new java.security.SecureRandom().nextBytes(key);
            raw.write(("GET " + WEBSOCKET_PATH + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nUpgrade: websocket\r\n"
                    + "Connection: Upgrade\r\nSec-WebSocket-Key: " + java.util.Base64.getEncoder().encodeToString(key)
                    + "\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            raw.flush();
            var response = new StringBuilder();
            while (!response.toString().endsWith("\r\n\r\n")) {
                int next = in.read();
                if (next < 0) throw new EOFException("WebSocket upgrade response ended early");
                response.append((char) next);
            }
            if (!response.toString().startsWith("HTTP/1.1 101")) {
                throw new IOException("WebSocket upgrade refused: " + response.toString().lines().findFirst().orElse(""));
            }
            byte[] mask = new byte[4];
            new java.util.Random(port).nextBytes(mask);
            output = new java.io.OutputStream() {
                private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

                @Override public void write(int value) { pending.write(value); }
                @Override public void write(byte[] bytes, int offset, int length) { pending.write(bytes, offset, length); }

                @Override public void flush() throws IOException {
                    if (pending.size() == 0) return;
                    byte[] payload = pending.toByteArray();
                    pending.reset();
                    raw.write(0x82); // FIN, binary
                    if (payload.length < 126) {
                        raw.write(0x80 | payload.length);
                    } else if (payload.length <= 0xFFFF) {
                        raw.write(0x80 | 126);
                        raw.write(payload.length >>> 8);
                        raw.write(payload.length);
                    } else {
                        raw.write(0x80 | 127);
                        for (int shift = 56; shift >= 0; shift -= 8) raw.write((int) ((long) payload.length >>> shift));
                    }
                    raw.write(mask);
                    for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
                    raw.write(payload);
                    raw.flush();
                }
            };
            input = new java.io.InputStream() {
                private long remaining;

                private boolean nextMessage() throws IOException {
                    while (remaining == 0) {
                        int first = in.read();
                        if (first < 0) return false;
                        int second = in.read();
                        if (second < 0) throw new EOFException("WebSocket frame header ended early");
                        int opcode = first & 0x0F;
                        long length = second & 0x7F;
                        if (length == 126) length = (in.read() << 8) | in.read();
                        else if (length == 127) {
                            length = 0;
                            for (int i = 0; i < 8; i++) length = (length << 8) | in.read();
                        }
                        if ((second & 0x80) != 0) throw new IOException("server frames must not be masked");
                        if (opcode == 0x8) return false;
                        if (opcode == 0x2 || opcode == 0x0) {
                            remaining = length;
                        } else if (in.skip(length) != length) {
                            throw new EOFException("WebSocket control frame ended early");
                        }
                    }
                    return true;
                }

                @Override public int read() throws IOException {
                    if (!nextMessage()) return -1;
                    int value = in.read();
                    if (value >= 0) remaining--;
                    return value;
                }

                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    if (length == 0) return 0;
                    if (!nextMessage()) return -1;
                    int read = in.read(bytes, offset, (int) Math.min(length, remaining));
                    if (read > 0) remaining -= read;
                    return read;
                }
            };
        }
    }

    /** How a phase's clients connect and what they send; direct phases always use plain TCP to the fake backend. */
    private record ClientOptions(boolean webSocket, javax.net.ssl.SSLContext tls, ModdedTraffic modded,
                                 boolean coalesce) {
        ClientOptions forPhase(boolean proxyPhase) {
            return proxyPhase ? this : new ClientOptions(false, null, modded, coalesce);
        }
    }

    /**
     * Fixed-seed traffic shaped like a large 1.7.10 modpack. Each client tick sends a mod custom payload, a position
     * packet and an on-ground packet. Each server tick answers with 6-14 small packets (mod custom payloads and tile
     * entity updates of 20-600 bytes), a 2-8 KiB custom payload on about one tick in three and a 30-60 KiB chunk on
     * about one in twenty. After each Position and Look the backend also sends a ~2 MiB burst of 30,000-byte custom
     * payloads, standing in for Forge registry sync. Every clientbound packet carries the tick sequence at a known
     * offset, so the client checks every byte. 256 templates repeat by sequence.
     */
    private static final class ModdedTraffic {
        private static final int TEMPLATES = 256;
        private static final int LOGIN_PACKETS = 70;
        private static final String[] CHANNELS = {"gregtech", "appliedenergistics2", "Forestry", "IC2",
                "Thaumcraft", "BuildCraft|TE", "OpenComputers", "journeymap"};

        /** A packet body; {@code stamp} is where the 4-byte sequence goes, or -1 for none. */
        record Packet(byte[] body, int stamp) { }

        final Packet[] login = new Packet[LOGIN_PACKETS];
        private final Packet[][] client = new Packet[TEMPLATES][];
        private final Packet[][] server = new Packet[TEMPLATES][];

        ModdedTraffic() {
            try {
                for (int t = 0; t < TEMPLATES; t++) {
                    var random = new java.util.SplittableRandom(0x5EED0000L + t);
                    client[t] = new Packet[] {
                            customPayload(0x17, channel(random), 10 + random.nextInt(71), random),
                            position(random), new Packet(new byte[] {0x03, 1}, -1)};
                    List<Packet> packets = new ArrayList<>();
                    int small = 6 + random.nextInt(9);
                    for (int i = 0; i < small; i++) {
                        packets.add(random.nextInt(10) < 7
                                ? customPayload(0x3F, channel(random), 20 + random.nextInt(281), random)
                                : tileEntity(100 + random.nextInt(501), random));
                    }
                    if (random.nextInt(3) == 0) {
                        packets.add(customPayload(0x3F, channel(random), 2048 + random.nextInt(6145), random));
                    }
                    if (random.nextInt(20) == 0) packets.add(chunk(30 * 1024 + random.nextInt(30 * 1024 + 1), random));
                    server[t] = packets.toArray(Packet[]::new);
                }
                var random = new java.util.SplittableRandom(0x10C1L);
                for (int i = 0; i < LOGIN_PACKETS; i++) login[i] = customPayload(0x3F, "gregtech", 30_000, random);
            } catch (IOException impossible) {
                throw new java.io.UncheckedIOException(impossible);
            }
        }

        Packet[] client(int sequence) { return client[Math.floorMod(sequence, TEMPLATES)]; }
        Packet[] server(int sequence) { return server[Math.floorMod(sequence, TEMPLATES)]; }

        String description(boolean coalesce) {
            long frames = 0, bytes = 0;
            for (Packet[] tick : server) for (Packet packet : tick) { frames++; bytes += packet.body().length; }
            long loginBytes = 0;
            for (Packet packet : login) loginBytes += packet.body().length;
            return String.format("Modded profile: per roundtrip the client sends 3 packets (%s) and the backend "
                            + "answers with %.1f packets / %.0f bytes on average over %d templates; frame counts "
                            + "include both directions. Login burst: %d packets / %d bytes after each Position and Look.",
                    coalesce ? "one write and flush per tick" : "one write and flush per packet",
                    frames / (double) TEMPLATES, bytes / (double) TEMPLATES, TEMPLATES, LOGIN_PACKETS, loginBytes);
        }

        byte[] frame(Packet packet, int sequence) throws IOException {
            byte[] body = packet.body().clone();
            if (packet.stamp() >= 0) stamp(body, packet.stamp(), sequence);
            return ProxySessionBenchmark.frame(body);
        }

        void verify(Packet expected, byte[] body, int sequence) throws IOException {
            byte[] want = expected.body();
            int at = expected.stamp();
            if (body.length != want.length) throw new IOException("length " + body.length + " expected " + want.length);
            if (Arrays.mismatch(body, 0, at, want, 0, at) >= 0
                    || Arrays.mismatch(body, at + 4, body.length, want, at + 4, want.length) >= 0) {
                throw new IOException("packet bytes differ from the template");
            }
            int actual = ((body[at] & 0xff) << 24) | ((body[at + 1] & 0xff) << 16)
                    | ((body[at + 2] & 0xff) << 8) | (body[at + 3] & 0xff);
            if (actual != sequence) throw new IOException("sequence " + actual + " expected " + sequence);
        }

        /** The backend reads the tick sequence from the client's custom payload, which each tick sends first. */
        static int sequenceOf(byte[] body) throws IOException {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            if (readVarInt(input) != 0x17) throw new IOException("expected the tick's custom payload first");
            readString(input, 20);
            DataInputStream data = new DataInputStream(input);
            data.readUnsignedShort();
            return data.readInt();
        }

        private static String channel(java.util.SplittableRandom random) {
            return CHANNELS[random.nextInt(CHANNELS.length)];
        }

        /** C17/S3F: channel, a short length (Forge's VarShort is the same two bytes below 32 KiB), then data. */
        private static Packet customPayload(int id, String channel, int length, java.util.SplittableRandom random)
                throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            writeVarInt(bytes, id);
            writeString(bytes, channel);
            new DataOutputStream(bytes).writeShort(length);
            int stamp = bytes.size();
            bytes.write(randomBytes(length, random));
            return new Packet(bytes.toByteArray(), stamp);
        }

        /** S35 Update Tile Entity: the sequence sits in x, then y, z, action and the NBT bytes. */
        private static Packet tileEntity(int nbtLength, java.util.SplittableRandom random) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            writeVarInt(bytes, 0x35);
            out.writeInt(0); out.writeShort(64); out.writeInt(random.nextInt(1 << 16)); out.writeByte(1);
            out.writeShort(nbtLength);
            bytes.write(randomBytes(nbtLength, random));
            return new Packet(bytes.toByteArray(), 1);
        }

        /** S21 Chunk Data: the sequence sits in the chunk x, then z, flags and the (already deflated) data. */
        private static Packet chunk(int dataLength, java.util.SplittableRandom random) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            writeVarInt(bytes, 0x21);
            out.writeInt(0); out.writeInt(random.nextInt(64)); out.writeBoolean(true);
            out.writeShort(0xFFFF); out.writeShort(0); out.writeInt(dataLength);
            bytes.write(randomBytes(dataLength, random));
            return new Packet(bytes.toByteArray(), 1);
        }

        /** C04 Player Position: x, feet y, stance, z, on ground. */
        private static Packet position(java.util.SplittableRandom random) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            writeVarInt(bytes, 0x04);
            double y = 64 + random.nextDouble();
            out.writeDouble(random.nextDouble() * 100); out.writeDouble(y); out.writeDouble(y + 1.62);
            out.writeDouble(random.nextDouble() * 100); out.writeBoolean(true);
            return new Packet(bytes.toByteArray(), -1);
        }

        private static byte[] randomBytes(int length, java.util.SplittableRandom random) {
            byte[] bytes = new byte[length];
            random.nextBytes(bytes);
            return bytes;
        }

        private static void stamp(byte[] body, int at, int sequence) {
            body[at] = (byte) (sequence >>> 24);
            body[at + 1] = (byte) (sequence >>> 16);
            body[at + 2] = (byte) (sequence >>> 8);
            body[at + 3] = (byte) sequence;
        }
    }

    /** A throwaway ECDSA P-256 key and self-signed certificate made with the JDK's keytool, for --websocket-tls. */
    private record BenchmarkTls(io.netty.handler.ssl.SslContext server, javax.net.ssl.SSLContext client,
                                String description) {
        static BenchmarkTls create() throws Exception {
            java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("moonbridge-bench-tls");
            java.nio.file.Path store = directory.resolve("bench.p12");
            String password = "benchmark";
            var keys = java.security.KeyStore.getInstance("PKCS12");
            try {
                Process keytool = new ProcessBuilder(
                        java.nio.file.Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                        "-genkeypair", "-alias", "bench", "-keyalg", "EC", "-groupname", "secp256r1",
                        "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "2",
                        "-storetype", "PKCS12", "-keystore", store.toString(),
                        "-storepass", password, "-keypass", password).redirectErrorStream(true).start();
                String output = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (keytool.waitFor() != 0) throw new IOException("keytool failed: " + output);
                try (var input = java.nio.file.Files.newInputStream(store)) { keys.load(input, password.toCharArray()); }
            } finally {
                java.nio.file.Files.deleteIfExists(store);
                java.nio.file.Files.deleteIfExists(directory);
            }
            var keyManagers = javax.net.ssl.KeyManagerFactory.getInstance(
                    javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keys, password.toCharArray());
            var trust = javax.net.ssl.TrustManagerFactory.getInstance(
                    javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            trust.init(keys);
            var client = javax.net.ssl.SSLContext.getInstance("TLS");
            client.init(null, trust.getTrustManagers(), null);
            var server = io.netty.handler.ssl.SslContextBuilder.forServer(keyManagers).build();
            return new BenchmarkTls(server, client, "proxy SslProvider "
                    + io.netty.handler.ssl.SslContext.defaultServerProvider() + ", ECDSA P-256 certificate");
        }
    }

    private static PlayerView awaitPlayer(ProxySessionListener proxy, String username) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SETUP_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            for (PlayerView player : proxy.online()) {
                if (player.username().equals(username)) return player;
            }
            Thread.sleep(5);
        }
        throw new IOException("proxy did not publish " + username);
    }

    private static void writeSequence(byte[] frame, int bodyOffset, int sequence) {
        // A fixed-width, big-endian sequence field follows the PLAY packet ID in each body.
        frame[bodyOffset + 1] = (byte) (sequence >>> 24);
        frame[bodyOffset + 2] = (byte) (sequence >>> 16);
        frame[bodyOffset + 3] = (byte) (sequence >>> 8);
        frame[bodyOffset + 4] = (byte) sequence;
    }

    private static void verifyEcho(byte[] expectedPayload, byte[] echo, int expectedSequence) throws IOException {
        if (echo.length != expectedPayload.length) throw new IOException("length " + echo.length
                + " expected " + expectedPayload.length);
        if ((echo[0] & 0xff) != 0x03) throw new IOException("packet id " + (echo[0] & 0xff) + " expected 3");
        int actualSequence = ((echo[1] & 0xff) << 24) | ((echo[2] & 0xff) << 16)
                | ((echo[3] & 0xff) << 8) | (echo[4] & 0xff);
        if (actualSequence != expectedSequence) throw new IOException("sequence " + actualSequence
                + " expected " + expectedSequence);
        for (int i = 5; i < echo.length; i++) {
            if (echo[i] != expectedPayload[i]) throw new IOException("payload byte " + i + " mismatch");
        }
    }

    private static final class FakeBackend implements AutoCloseable {
        private final ServerSocket server = new ServerSocket();
        private final ExecutorService clients;
        private final int entityId;
        private final int burst;
        private final List<Socket> active = java.util.Collections.synchronizedList(new ArrayList<>());
        private volatile boolean closed;
        private Thread acceptThread;

        private final ModdedTraffic modded;

        FakeBackend(int concurrency, int entityId, int burst, ModdedTraffic modded) throws IOException {
            this.entityId = entityId;
            this.burst = burst;
            this.modded = modded;
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
                writeFrame(output, joinGame(entityId));
                writeFrame(output, positionAndLook());
                if (modded != null) {
                    ByteArrayOutputStream batch = new ByteArrayOutputStream();
                    for (int i = 0; i < modded.login.length; i++) batch.write(modded.frame(modded.login[i], i));
                    batch.writeTo(output);
                    output.flush();
                    while (!closed && !socket.isClosed()) {
                        int sequence = ModdedTraffic.sequenceOf(readFrame(input));
                        for (int k = 1; k < modded.client(sequence).length; k++) readFrame(input);
                        batch.reset();
                        for (ModdedTraffic.Packet packet : modded.server(sequence))
                            batch.write(modded.frame(packet, sequence));
                        batch.writeTo(output);
                        output.flush();
                    }
                } else if (burst == 1) {
                    while (!closed && !socket.isClosed()) {
                        byte[] body = readFrame(input);
                        writeFrame(output, body);
                    }
                } else {
                    ByteArrayOutputStream echoes = new ByteArrayOutputStream();
                    while (!closed && !socket.isClosed()) {
                        echoes.reset();
                        for (int k = 0; k < burst; k++) {
                            byte[] body = readFrame(input);
                            writeVarInt(echoes, body.length);
                            echoes.write(body);
                        }
                        echoes.writeTo(output);
                        output.flush();
                    }
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

    /** CPU time consumed by the proxy's session I/O event loops; the direct phase leaves them idle. */
    private static long proxyIoCpuNanos() {
        var threads = java.lang.management.ManagementFactory.getThreadMXBean();
        long total = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (!thread.getName().startsWith("moonbridge-session-io")) continue;
            long cpu = threads.getThreadCpuTime(thread.threadId());
            if (cpu > 0) total += cpu;
        }
        return total;
    }

    /** Host-wide TCP OutSegs from /proc/net/snmp (Linux only), or -1 when unavailable. */
    private static long tcpOutSegments() {
        try {
            List<String> lines = java.nio.file.Files.readAllLines(java.nio.file.Path.of("/proc/net/snmp"));
            for (int i = 0; i + 1 < lines.size(); i++) {
                if (!lines.get(i).startsWith("Tcp:") || !lines.get(i + 1).startsWith("Tcp:")) continue;
                List<String> keys = List.of(lines.get(i).trim().split("\\s+"));
                String[] values = lines.get(i + 1).trim().split("\\s+");
                int column = keys.indexOf("OutSegs");
                return column < 0 ? -1 : Long.parseLong(values[column]);
            }
        } catch (IOException | RuntimeException unavailable) {
            return -1;
        }
        return -1;
    }

    private static long percentile(long[] sorted, double quantile) {
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(quantile * sorted.length) - 1)];
    }

    private static int intArg(String[] args, String key, int fallback) {
        int found = fallback;
        boolean seen = false;
        List<String> known = List.of("connections", "messages", "warmup", "payload", "repeats", "window",
                "mode", "command-interceptor", "burst", "transport", "websocket", "websocket-tls", "profile",
                "client-coalesce");
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

    private static String stringArg(String[] args, String key, String fallback) {
        for (int i = 0; i + 1 < args.length; i++) if (args[i].equals("--" + key)) return args[i + 1];
        return fallback;
    }

    private static String modeArg(String[] args) {
        String mode = "initial";
        boolean seen = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--mode")) {
                if (seen || i + 1 >= args.length) throw new IllegalArgumentException("missing or duplicate --mode");
                mode = args[++i];
                seen = true;
            } else if (args[i].startsWith("--")) i++;
        }
        if (!mode.equals("initial") && !mode.equals("post-transfer"))
            throw new IllegalArgumentException("mode must be initial or post-transfer");
        return mode;
    }
}

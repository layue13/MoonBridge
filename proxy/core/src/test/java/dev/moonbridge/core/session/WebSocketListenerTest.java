package dev.moonbridge.core.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.moonbridge.api.AccessDecision;
import dev.moonbridge.api.PlacementDecision;
import dev.moonbridge.api.event.ConnectionAdmissionEvent;
import dev.moonbridge.api.event.Event;
import dev.moonbridge.app.ProxyConfiguration;
import dev.moonbridge.app.ProxyConfigurationLoader;
import dev.moonbridge.core.auth.MinecraftEncryptionRequest;
import dev.moonbridge.core.auth.MinecraftEncryptionResponse;
import dev.moonbridge.core.auth.VerifiedProfile;
import dev.moonbridge.core.backend.BackendId;
import dev.moonbridge.core.backend.BackendOwner;
import dev.moonbridge.core.backend.BackendRegistration;
import dev.moonbridge.core.backend.InMemoryBackendCatalog;
import dev.moonbridge.core.event.EventDispatcher;
import dev.moonbridge.core.websocket.ClientAddressPolicy;
import dev.moonbridge.core.websocket.WebSocketFrontend;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.pkitesting.CertificateBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Minecraft over WebSocket next to the TCP listener, through real sockets on both sides. */
final class WebSocketListenerTest {
    private static final SocketAddress LOOPBACK_ANY_PORT = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);

    private static WebSocketFrontend.Settings settings(String path, ClientAddressPolicy addresses) {
        return new WebSocketFrontend.Settings(path, null, addresses, Duration.ofSeconds(5));
    }

    private static int port(io.netty.channel.Channel channel) {
        return ((InetSocketAddress) channel.localAddress()).getPort();
    }

    /** Records connection admission addresses and allows everything. */
    private static EventDispatcher recordingAdmission(List<InetSocketAddress> seen) {
        return new EventDispatcher() {
            @Override public boolean hasSubscribers(Class<?> eventType) { return eventType == ConnectionAdmissionEvent.class; }
            @SuppressWarnings("unchecked")
            @Override public <R> CompletionStage<R> dispatch(Event<R> event) {
                seen.add(((ConnectionAdmissionEvent) event).remoteAddress());
                return (CompletionStage<R>) CompletableFuture.completedFuture(AccessDecision.allow());
            }
        };
    }

    /** Offline backend that logs each connection in, starts PLAY and echoes every client frame. */
    private static Thread echoBackend(ServerSocket server, int connections, CompletableFuture<Void> done) {
        Thread thread = new Thread(() -> {
            List<Thread> handlers = new CopyOnWriteArrayList<>();
            try {
                for (int i = 0; i < connections; i++) {
                    Socket socket = server.accept();
                    Thread handler = new Thread(() -> {
                        try (socket) {
                            socket.setSoTimeout(5000);
                            var input = new DataInputStream(socket.getInputStream());
                            var output = new DataOutputStream(socket.getOutputStream());
                            readFrame(input);
                            var login = new ByteArrayInputStream(readFrame(input));
                            readVarInt(login);
                            String username = readString(login, 16);
                            var success = new ByteArrayOutputStream();
                            writeVarInt(success, 2);
                            writeString(success, UUID.nameUUIDFromBytes(("OfflinePlayer:" + username)
                                    .getBytes(StandardCharsets.UTF_8)).toString());
                            writeString(success, username);
                            writeFrame(output, success.toByteArray());
                            writeJoinAndPosition(output, 42);
                            while (true) {
                                byte[] frame;
                                try { frame = readFrame(input); }
                                catch (java.io.EOFException closed) { break; }
                                writeFrame(output, frame);
                            }
                        } catch (Throwable failure) {
                            done.completeExceptionally(failure);
                        }
                    }, "fake-websocket-backend-" + i);
                    handler.setDaemon(true);
                    handler.start();
                    handlers.add(handler);
                }
                for (Thread handler : handlers) handler.join(10_000);
                done.complete(null);
            } catch (Throwable failure) {
                done.completeExceptionally(failure);
            }
        }, "fake-websocket-backend");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void writeJoinAndPosition(DataOutputStream output, int entityId) throws Exception {
        writeFrame(output, new byte[]{0x01, 0, 0, 0, (byte) entityId, 0, 0, 1, 20, 7, 'd', 'e', 'f', 'a', 'u', 'l', 't'});
        var position = new ByteArrayOutputStream();
        var data = new DataOutputStream(position);
        data.writeByte(0x08);
        data.writeDouble(0);
        data.writeDouble(64);
        data.writeDouble(0);
        data.writeFloat(0);
        data.writeFloat(0);
        data.writeByte(0);
        writeFrame(output, position.toByteArray());
    }

    private static void readLoginAndPlayStart(DataInputStream input) throws Exception {
        assertEquals(2, readVarInt(readFrame(input)));
        assertEquals(1, readVarInt(readFrame(input)));
        assertEquals(8, readVarInt(readFrame(input)));
    }

    @Test
    void tcpAndWebSocketPlayersShareOneProxy() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        var admitted = new CopyOnWriteArrayList<InetSocketAddress>();
        var backendDone = new CompletableFuture<Void>();
        try (ServerSocket backendServer = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            echoBackend(backendServer, 2, backendDone);
            catalog.register(new BackendRegistration(new BackendId("lobby"), new BackendOwner("static", 0),
                    URI.create("tcp://127.0.0.1:" + backendServer.getLocalPort()), Map.of(), Map.of()));
            var listener = new ProxySessionListener(LOOPBACK_ANY_PORT, catalog);
            listener.setPlacement(player -> CompletableFuture.completedFuture(Optional.of(PlacementDecision.select("lobby"))));
            listener.setEvents(recordingAdmission(admitted), Duration.ofSeconds(5));
            listener.setWebSocket(LOOPBACK_ANY_PORT, settings("/mc",
                    new ClientAddressPolicy(List.of("127.0.0.0/8"), "X-Forwarded-For")));
            try {
                int tcpPort = port(listener.start().toCompletableFuture().get(5, TimeUnit.SECONDS));
                int wsPort = port(listener.webSocketChannel());
                try (Socket tcp = new Socket(InetAddress.getLoopbackAddress(), tcpPort);
                     var ws = TestWebSocketClient.connect(new Socket(InetAddress.getLoopbackAddress(), wsPort), "/mc",
                             Map.of("X-Forwarded-For", "6.6.6.6, 198.51.100.9"))) {
                    tcp.setSoTimeout(5000);
                    sendLogin(new DataOutputStream(tcp.getOutputStream()), "TcpPlayer");
                    var tcpInput = new DataInputStream(tcp.getInputStream());
                    readLoginAndPlayStart(tcpInput);

                    // The handshake is split across two messages and shares the second with the login frame.
                    var hello = new ByteArrayOutputStream();
                    var helloData = new DataOutputStream(hello);
                    sendLogin(helloData, "WsPlayer");
                    byte[] loginBytes = hello.toByteArray();
                    ws.send(TestWebSocketClient.BINARY, java.util.Arrays.copyOfRange(loginBytes, 0, 3));
                    ws.send(TestWebSocketClient.BINARY, java.util.Arrays.copyOfRange(loginBytes, 3, loginBytes.length));
                    var wsInput = new DataInputStream(ws.input());
                    readLoginAndPlayStart(wsInput);
                    awaitCondition(() -> listener.online().size() == 2);

                    // Two frames in one message, then one frame split across two messages around a ping.
                    var batch = new ByteArrayOutputStream();
                    batch.write(framed(new byte[]{0x03, 0x31}));
                    batch.write(framed(new byte[]{0x03, 0x32}));
                    ws.send(TestWebSocketClient.BINARY, batch.toByteArray());
                    assertArrayEquals(new byte[]{0x03, 0x31}, readFrame(wsInput));
                    assertArrayEquals(new byte[]{0x03, 0x32}, readFrame(wsInput));
                    byte[] split = framed(new byte[]{0x03, 0x33, 0x34, 0x35});
                    ws.send(TestWebSocketClient.BINARY, java.util.Arrays.copyOfRange(split, 0, 2));
                    ws.send(TestWebSocketClient.PING, new byte[]{7});
                    ws.send(TestWebSocketClient.BINARY, java.util.Arrays.copyOfRange(split, 2, split.length));
                    assertArrayEquals(new byte[]{0x03, 0x33, 0x34, 0x35}, readFrame(wsInput));
                    assertEquals(TestWebSocketClient.PONG, ws.lastControlOpcode());

                    writeFrame(new DataOutputStream(tcp.getOutputStream()), new byte[]{0x03, 0x41});
                    assertArrayEquals(new byte[]{0x03, 0x41}, readFrame(tcpInput));

                    assertEquals(2, admitted.size());
                    assertTrue(admitted.stream().anyMatch(address -> address.equals(tcp.getLocalSocketAddress())));
                    assertTrue(admitted.stream().anyMatch(address ->
                            address.getAddress().getHostAddress().equals("198.51.100.9")));
                }
                backendDone.get(10, TimeUnit.SECONDS);
                awaitCondition(() -> listener.allSessions().isEmpty());
            } finally {
                listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void untrustedPeerCannotClaimAnotherAddress() throws Exception {
        var admitted = new CopyOnWriteArrayList<InetSocketAddress>();
        var listener = new ProxySessionListener(LOOPBACK_ANY_PORT, new InMemoryBackendCatalog());
        listener.setPlacement(player -> CompletableFuture.completedFuture(Optional.empty()));
        listener.setEvents(recordingAdmission(admitted), Duration.ofSeconds(5));
        listener.setWebSocket(LOOPBACK_ANY_PORT, settings("/", new ClientAddressPolicy(List.of("10.0.0.0/8"), "CF-Connecting-IP")));
        try {
            listener.start().toCompletableFuture().get(5, TimeUnit.SECONDS);
            try (var ws = TestWebSocketClient.connect(new Socket(InetAddress.getLoopbackAddress(),
                    port(listener.webSocketChannel())), "/", Map.of("CF-Connecting-IP", "198.51.100.9"))) {
                var status = requestStatus(ws.input(), ws.output());
                assertEquals("MoonBridge", status.path("description").path("text").asText());
                assertEquals(1, admitted.size());
                assertEquals("127.0.0.1", admitted.get(0).getAddress().getHostAddress());
            }
        } finally {
            listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void onlineModeEncryptsInsideWebSocketAndForwardsTheTrustedAddress() throws Exception {
        UUID uuid = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        var catalog = new InMemoryBackendCatalog();
        var verifiedIp = new CompletableFuture<String>();
        var backendDone = new CompletableFuture<Void>();
        try (ServerSocket backendServer = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            Thread backend = new Thread(() -> {
                try (Socket socket = backendServer.accept()) {
                    socket.setSoTimeout(5000);
                    var input = new DataInputStream(socket.getInputStream());
                    var output = new DataOutputStream(socket.getOutputStream());
                    var handshake = new ByteArrayInputStream(readFrame(input));
                    readVarInt(handshake);
                    readVarInt(handshake);
                    String[] fields = readString(handshake, 32767).split("\u0000", -1);
                    // Bungee legacy forwarding carries the player's address, not the CDN edge's.
                    assertEquals("198.51.100.9", fields[1]);
                    readFrame(input);
                    var success = new ByteArrayOutputStream();
                    writeVarInt(success, 2);
                    writeString(success, uuid.toString());
                    writeString(success, "Alice");
                    writeFrame(output, success.toByteArray());
                    writeJoinAndPosition(output, 100);
                    writeFrame(output, readFrame(input));
                    assertEquals(-1, input.read());
                    backendDone.complete(null);
                } catch (Throwable failure) {
                    backendDone.completeExceptionally(failure);
                }
            }, "fake-online-websocket-backend");
            backend.setDaemon(true);
            backend.start();
            catalog.register(new BackendRegistration(new BackendId("lobby"), new BackendOwner("static", 0),
                    URI.create("tcp://127.0.0.1:" + backendServer.getLocalPort()), Map.of(), Map.of()));
            var listener = new ProxySessionListener(LOOPBACK_ANY_PORT, catalog, (name, hash, ip) -> {
                verifiedIp.complete(ip);
                return CompletableFuture.completedFuture(Optional.of(new VerifiedProfile(uuid, "Alice", List.of())));
            });
            listener.setPlacement(player -> CompletableFuture.completedFuture(Optional.of(PlacementDecision.select("lobby"))));
            listener.setWebSocket(LOOPBACK_ANY_PORT, settings("/mc",
                    new ClientAddressPolicy(List.of("127.0.0.1"), "CF-Connecting-IP")));
            try {
                listener.start().toCompletableFuture().get(5, TimeUnit.SECONDS);
                try (var ws = TestWebSocketClient.connect(new Socket(InetAddress.getLoopbackAddress(),
                        port(listener.webSocketChannel())), "/mc", Map.of("CF-Connecting-IP", "198.51.100.9"))) {
                    var clearOutput = new DataOutputStream(ws.output());
                    sendLogin(clearOutput, "Alice");
                    var requestBytes = Unpooled.wrappedBuffer(readFrame(new DataInputStream(ws.input())));
                    MinecraftEncryptionRequest request;
                    try { request = MinecraftEncryptionRequest.decode(requestBytes); }
                    finally { requestBytes.release(); }
                    byte[] secret = new byte[16];
                    java.util.Arrays.fill(secret, (byte) 0x42);
                    var publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(request.publicKey()));
                    Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                    rsa.init(Cipher.ENCRYPT_MODE, publicKey);
                    var encoded = new MinecraftEncryptionResponse(rsa.doFinal(secret), rsa.doFinal(request.verifyToken()))
                            .encode(UnpooledByteBufAllocator.DEFAULT);
                    byte[] response = new byte[encoded.readableBytes()];
                    encoded.readBytes(response);
                    encoded.release();
                    writeFrame(clearOutput, response);

                    var input = new DataInputStream(decryptingInput(ws.input(), aes(secret, Cipher.DECRYPT_MODE)));
                    assertEquals("198.51.100.9", verifiedIp.get(5, TimeUnit.SECONDS));
                    readLoginAndPlayStart(input);
                    Cipher encryptor = aes(secret, Cipher.ENCRYPT_MODE);
                    clearOutput.write(encryptor.update(framed(new byte[]{0x03, 0x33})));
                    assertArrayEquals(new byte[]{0x03, 0x33}, readFrame(input));
                }
                backendDone.get(5, TimeUnit.SECONDS);
            } finally {
                listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void rejectedRequestsAndPendingUpgradesReturnTheirConnectionSlots() throws Exception {
        var listener = new ProxySessionListener(LOOPBACK_ANY_PORT, new InMemoryBackendCatalog());
        listener.setPlacement(player -> CompletableFuture.completedFuture(Optional.empty()));
        listener.setMaxConnections(1);
        listener.setWebSocket(LOOPBACK_ANY_PORT, settings("/mc", ClientAddressPolicy.direct()));
        try {
            int tcpPort = port(listener.start().toCompletableFuture().get(5, TimeUnit.SECONDS));
            int wsPort = port(listener.webSocketChannel());
            try (Socket wrongPath = new Socket(InetAddress.getLoopbackAddress(), wsPort)) {
                wrongPath.setSoTimeout(5000);
                assertTrue(TestWebSocketClient.upgrade(wrongPath, "/other", Map.of()).startsWith("HTTP/1.1 404"));
                assertEquals(-1, wrongPath.getInputStream().read());
            }
            awaitCondition(() -> listener.allSessions().isEmpty());
            try (var text = TestWebSocketClient.connect(new Socket(InetAddress.getLoopbackAddress(), wsPort), "/mc", Map.of())) {
                text.send(TestWebSocketClient.TEXT, "hello".getBytes(StandardCharsets.UTF_8));
                assertEquals(-1, text.input().read());
            }
            awaitCondition(() -> listener.allSessions().isEmpty());

            // A connection that never upgrades holds its slot, so the TCP listener refuses the next player.
            try (Socket idle = new Socket(InetAddress.getLoopbackAddress(), wsPort)) {
                awaitCondition(() -> listener.allSessions().size() == 1);
                try (Socket refused = new Socket(InetAddress.getLoopbackAddress(), tcpPort)) {
                    refused.setSoTimeout(5000);
                    assertEquals(-1, refused.getInputStream().read());
                }
            }
            awaitCondition(() -> listener.allSessions().isEmpty());
            try (Socket tcp = new Socket(InetAddress.getLoopbackAddress(), tcpPort)) {
                tcp.setSoTimeout(5000);
                assertEquals("MoonBridge", requestStatus(tcp.getInputStream(), tcp.getOutputStream())
                        .path("description").path("text").asText());
            }
        } finally {
            listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void idleConnectionIsClosedAtTheHandshakeDeadline() throws Exception {
        var listener = new ProxySessionListener(LOOPBACK_ANY_PORT, new InMemoryBackendCatalog());
        listener.setPlacement(player -> CompletableFuture.completedFuture(Optional.empty()));
        listener.setWebSocket(LOOPBACK_ANY_PORT,
                new WebSocketFrontend.Settings("/", null, ClientAddressPolicy.direct(), Duration.ofMillis(200)));
        try {
            listener.start().toCompletableFuture().get(5, TimeUnit.SECONDS);
            try (Socket idle = new Socket(InetAddress.getLoopbackAddress(), port(listener.webSocketChannel()))) {
                idle.setSoTimeout(5000);
                assertEquals(-1, idle.getInputStream().read());
            }
            awaitCondition(() -> listener.allSessions().isEmpty());
        } finally {
            listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void secureWebSocketLoadsPemMaterialRelativeToTheConfiguration(@TempDir Path directory) throws Exception {
        var certificate = new CertificateBuilder().subject("CN=localhost").addSanDnsName("localhost")
                .rsa2048().setIsCertificateAuthority(true).buildSelfSigned();
        Files.createDirectories(directory.resolve("certs"));
        Files.writeString(directory.resolve("certs/chain.pem"), certificate.getCertificatePathPEM());
        Files.writeString(directory.resolve("certs/key.pem"), certificate.getPrivateKeyPEM());
        var configuration = new ProxyConfiguration("127.0.0.1:0", ProxyConfiguration.Authentication.OFFLINE, List.of(),
                null, null, false, null, null, null, null,
                new ProxyConfiguration.WebSocket("127.0.0.1:1", "/mc",
                        new ProxyConfiguration.Tls("certs/chain.pem", "certs/key.pem"), null, null, null));
        var settings = new ProxyConfigurationLoader().loadWebSocket(configuration, directory.resolve("moonbridge.yml"));

        var listener = new ProxySessionListener(LOOPBACK_ANY_PORT, new InMemoryBackendCatalog());
        listener.setPlacement(player -> CompletableFuture.completedFuture(Optional.empty()));
        listener.setWebSocket(LOOPBACK_ANY_PORT, settings);
        try {
            listener.start().toCompletableFuture().get(5, TimeUnit.SECONDS);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, certificate.toTrustManagerFactory().getTrustManagers(), null);
            var socket = (SSLSocket) context.getSocketFactory().createSocket(InetAddress.getLoopbackAddress(),
                    port(listener.webSocketChannel()));
            var parameters = socket.getSSLParameters();
            parameters.setServerNames(List.of(new SNIHostName("localhost")));
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
            socket.setSoTimeout(5000);
            try (var ws = TestWebSocketClient.connect(socket, "/mc", Map.of())) {
                assertEquals("MoonBridge", requestStatus(ws.input(), ws.output()).path("description").path("text").asText());
            }
        } finally {
            listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode requestStatus(InputStream in, OutputStream out) throws Exception {
        var output = new DataOutputStream(out);
        var handshake = new ByteArrayOutputStream();
        writeVarInt(handshake, 0);
        writeVarInt(handshake, 5);
        writeString(handshake, "localhost");
        handshake.write(0);
        handshake.write(1);
        writeVarInt(handshake, 1);
        writeFrame(output, handshake.toByteArray());
        writeFrame(output, new byte[]{0});
        var response = new ByteArrayInputStream(readFrame(new DataInputStream(in)));
        assertEquals(0, readVarInt(response));
        return new ObjectMapper().readTree(readString(response, 32767));
    }

    private static void sendLogin(DataOutputStream output, String username) throws Exception {
        var hello = new ByteArrayOutputStream();
        writeVarInt(hello, 0);
        writeVarInt(hello, 5);
        writeString(hello, "localhost");
        hello.write(0);
        hello.write(1);
        writeVarInt(hello, 2);
        var login = new ByteArrayOutputStream();
        writeVarInt(login, 0);
        writeString(login, username);
        // One write, so a WebSocket client sends the handshake and login in a single message.
        var both = new ByteArrayOutputStream();
        both.write(framed(hello.toByteArray()));
        both.write(framed(login.toByteArray()));
        output.write(both.toByteArray());
        output.flush();
    }

    private static Cipher aes(byte[] secret, int mode) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CFB8/NoPadding");
        cipher.init(mode, new SecretKeySpec(secret, "AES"), new IvParameterSpec(secret));
        return cipher;
    }

    /** Byte-at-a-time CFB8 decryption; CipherInputStream would block for a full buffer. */
    private static InputStream decryptingInput(InputStream encrypted, Cipher cipher) {
        return new InputStream() {
            @Override public int read() throws IOException {
                int next = encrypted.read();
                if (next < 0) return -1;
                byte[] decoded = cipher.update(new byte[]{(byte) next});
                if (decoded == null || decoded.length != 1) throw new IOException("CFB8 did not emit one byte");
                return decoded[0] & 0xff;
            }
        };
    }

    private static void awaitCondition(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }

    private static byte[] readFrame(DataInputStream input) throws IOException {
        int length = readVarInt(input);
        byte[] frame = input.readNBytes(length);
        if (frame.length != length) throw new java.io.EOFException();
        return frame;
    }

    private static byte[] framed(byte[] payload) throws IOException {
        var output = new ByteArrayOutputStream();
        writeVarInt(output, payload.length);
        output.write(payload);
        return output.toByteArray();
    }

    private static void writeFrame(DataOutputStream output, byte[] payload) throws IOException {
        output.write(framed(payload));
        output.flush();
    }

    private static int readVarInt(byte[] bytes) throws IOException { return readVarInt(new ByteArrayInputStream(bytes)); }

    private static int readVarInt(InputStream input) throws IOException {
        int value = 0;
        int shift = 0;
        int current;
        do {
            current = input.read();
            if (current < 0 || shift >= 35) throw new java.io.EOFException();
            value |= (current & 0x7f) << shift;
            shift += 7;
        } while ((current & 0x80) != 0);
        return value;
    }

    private static String readString(InputStream input, int maxLength) throws IOException {
        int length = readVarInt(input);
        if (length < 0 || length > maxLength * 4) throw new IllegalArgumentException("bad test string");
        return new String(input.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static void writeString(ByteArrayOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length);
        output.write(bytes);
    }

    private static void writeVarInt(ByteArrayOutputStream output, int value) {
        do {
            int part = value & 0x7f;
            value >>>= 7;
            output.write(value == 0 ? part : part | 0x80);
        } while (value != 0);
    }
}

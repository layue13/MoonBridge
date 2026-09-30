package dev.moonbridge.core.session;

import dev.moonbridge.api.PlacementDecision;
import dev.moonbridge.api.PlayerView;
import dev.moonbridge.api.TransferStatus;
import dev.moonbridge.api.event.Event;
import dev.moonbridge.api.event.TransferDecision;
import dev.moonbridge.api.event.TransferContext;
import dev.moonbridge.api.event.TransferPreparingEvent;
import dev.moonbridge.core.backend.BackendId;
import dev.moonbridge.core.backend.BackendOwner;
import dev.moonbridge.core.backend.BackendRegistration;
import dev.moonbridge.core.backend.InMemoryBackendCatalog;
import dev.moonbridge.core.event.EventDispatcher;
import dev.moonbridge.core.event.PreparedTransfer;
import dev.moonbridge.core.event.TransferPreparation;
import org.junit.jupiter.api.Test;

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
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SourceReleaseTransferTest {
    private static final String USERNAME = "ReleasePlayer";
    private static final byte[] BUFFERED_CLIENT_FRAME = {0x03, 0x55};

    @Test
    void waitsForSaveAcknowledgementAfterExactSourceEofBeforeTargetLoginAndDiscardsFrames() throws Exception {
        var acknowledgement = new CompletableFuture<Void>();
        var callbackEntered = new CompletableFuture<Void>();
        var preparedContext = new AtomicReference<TransferContext>();
        try (TransferFixture fixture = new TransferFixture(event -> {
            preparedContext.set(event.context());
            return CompletableFuture.completedFuture(releaseSource(() -> {
                callbackEntered.complete(null);
                return acknowledgement;
            }));
        })) {
            try (Client client = fixture.login()) {
                PlayerView player = fixture.player();
                var transfer = fixture.listener.transfer(player.identity(), "target").toCompletableFuture();
                fixture.targetAccepted.get(5, TimeUnit.SECONDS);
                fixture.sourceClosed.get(5, TimeUnit.SECONDS);
                callbackEntered.get(5, TimeUnit.SECONDS);
                assertNotNull(preparedContext.get(), "preparation context must be captured for the close callback");
                assertEquals("source", preparedContext.get().source().name());
                PlayerView releasedView = fixture.listener.find(player.identity()).orElseThrow();
                assertTrue(releasedView.currentServer().isEmpty(), "source EOF must clear the current backend view");
                assertTrue(fixture.listener.online().stream().anyMatch(online -> online.identity().equals(player.identity())),
                        "source EOF must not emit a player-disconnected state while the frontend remains connected");
                assertFalse(fixture.targetLogin.isDone(), "target Login Start must wait for the save acknowledgement");
                assertFalse(transfer.isDone());

                writeFrame(client.output(), BUFFERED_CLIENT_FRAME);
                client.setSoTimeout(150);
                assertThrows(SocketTimeoutException.class, () -> client.input().read(),
                        "source EOF must not be forwarded to the client while the release callback waits");
                assertFalse(fixture.targetLogin.isDone());

                acknowledgement.complete(null);
                fixture.targetLogin.get(5, TimeUnit.SECONDS);
                assertEquals(TransferStatus.NETWORK_READY, transfer.get(5, TimeUnit.SECONDS).status());
                client.setSoTimeout(5000);
                assertEquals(7, packetId(readFrame(client.input())));
                assertEquals(7, packetId(readFrame(client.input())));
                assertEquals(8, packetId(readFrame(client.input())),
                        "the candidate's Position and Look must reach the client after cutover");
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> fixture.targetFirstClientFrame.get(200, TimeUnit.MILLISECONDS),
                        "the frame read while the source-release gate is held must not be replayed");
            }
        }
    }

    @Test
    void preparationDenialBeforeReleaseKeepsTheSourceRelayUsable() throws Exception {
        var rejected = new CompletableFuture<PreparedTransfer>();
        rejected.completeExceptionally(new IllegalStateException("transfer denied"));
        try (TransferFixture fixture = new TransferFixture(event -> rejected)) {
            try (Client client = fixture.login()) {
                PlayerView player = fixture.player();
                var result = fixture.listener.transfer(player.identity(), "target").toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);
                assertEquals(TransferStatus.FAILED, result.status());
                assertEquals("source", fixture.listener.find(player.identity())
                        .flatMap(PlayerView::currentServer).orElseThrow());
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> fixture.sourceClosed.get(150, TimeUnit.MILLISECONDS));
                writeFrame(fixture.sourceOutput.get(), new byte[]{0x03, 0x44});
                assertArrayEquals(new byte[]{0x03, 0x44}, readFrame(client.input()));
                assertFalse(fixture.targetLogin.isDone(), "denial must keep target Login Start deferred");
            }
        }
    }

    @Test
    void sourceReleaseCallbackFailureDisconnectsAfterSourceEof() throws Exception {
        try (TransferFixture fixture = new TransferFixture(preparation(releaseSource(
                () -> CompletableFuture.failedFuture(new IllegalStateException("save failed")))))) {
            try (Client client = fixture.login()) {
                PlayerView player = fixture.player();
                var transfer = fixture.listener.transfer(player.identity(), "target").toCompletableFuture();
                fixture.sourceClosed.get(5, TimeUnit.SECONDS);
                assertEquals(TransferStatus.FAILED, transfer.get(5, TimeUnit.SECONDS).status());
                assertEquals(-1, client.input().read());
                assertFalse(fixture.targetLogin.isDone(), "failed source save must prevent target Login Start");
            }
        }
    }

    @Test
    void disconnectMakesLatePreparationCompletionStale() throws Exception {
        var pending = new CompletableFuture<PreparedTransfer>();
        var sourceReleasedCalls = new AtomicInteger();
        try (TransferFixture fixture = new TransferFixture(event -> pending)) {
            Client client = fixture.login();
            try {
                PlayerView player = fixture.player();
                fixture.listener.transfer(player.identity(), "target");
                fixture.targetAccepted.get(5, TimeUnit.SECONDS);
                client.close();
                fixture.sourceClosed.get(5, TimeUnit.SECONDS);
                pending.complete(releaseSource(() -> {
                    sourceReleasedCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                }));
                Thread.sleep(100);
                assertEquals(0, sourceReleasedCalls.get(), "a stale callback must not reopen the transfer");
                assertFalse(fixture.targetLogin.isDone(), "a disconnected session must not start destination login");
            } finally {
                client.close();
            }
        }
    }

    @Test
    void targetRegistrationReplacementWhileSaveAcknowledgementWaitsDisconnectsWithoutLoginStart() throws Exception {
        var acknowledgement = new CompletableFuture<Void>();
        var callbackEntered = new CompletableFuture<Void>();
        try (TransferFixture fixture = new TransferFixture(preparation(releaseSource(
                () -> { callbackEntered.complete(null); return acknowledgement; })))) {
            try (Client client = fixture.login()) {
                PlayerView player = fixture.player();
                var transfer = fixture.listener.transfer(player.identity(), "target").toCompletableFuture();
                fixture.targetAccepted.get(5, TimeUnit.SECONDS);
                fixture.sourceClosed.get(5, TimeUnit.SECONDS);
                callbackEntered.get(5, TimeUnit.SECONDS);
                fixture.replaceTargetRegistration();
                acknowledgement.complete(null);
                assertEquals(TransferStatus.FAILED, transfer.get(5, TimeUnit.SECONDS).status());
                assertEquals(-1, client.input().read());
                assertFalse(fixture.targetLogin.isDone(), "stale target generation must not receive Login Start");
            }
        }
    }

    @Test
    void frontendDisconnectDuringReleaseConfirmationCancelsItAndNeverStartsTargetLogin() throws Exception {
        var acknowledgement = new CompletableFuture<Void>();
        var callbackEntered = new CompletableFuture<Void>();
        var cancelled = new CompletableFuture<Void>();
        acknowledgement.whenComplete((ignored, failure) -> {
            if (acknowledgement.isCancelled()) cancelled.complete(null);
        });
        try (TransferFixture fixture = new TransferFixture(preparation(releaseSource(
                () -> { callbackEntered.complete(null); return acknowledgement; })))) {
            Client client = fixture.login();
            try {
                PlayerView player = fixture.player();
                fixture.listener.transfer(player.identity(), "target");
                fixture.targetAccepted.get(5, TimeUnit.SECONDS);
                fixture.sourceClosed.get(5, TimeUnit.SECONDS);
                callbackEntered.get(5, TimeUnit.SECONDS);
                client.close();
                cancelled.get(5, TimeUnit.SECONDS);
                acknowledgement.complete(null);
                assertFalse(fixture.targetLogin.isDone(), "a stale release callback must not start target login");
            } finally {
                client.close();
            }
        }
    }

    @Test
    void candidateDeathBeforeSourceReleaseLeavesSourceUsable() throws Exception {
        var pending = new CompletableFuture<PreparedTransfer>();
        try (TransferFixture fixture = new TransferFixture(event -> pending)) {
            try (Client client = fixture.login()) {
                PlayerView player = fixture.player();
                var transfer = fixture.listener.transfer(player.identity(), "target").toCompletableFuture();
                fixture.targetAccepted.get(5, TimeUnit.SECONDS);
                writeFrame(client.output(), BUFFERED_CLIENT_FRAME);
                assertArrayEquals(BUFFERED_CLIENT_FRAME, fixture.sourceClientFrames.poll(5, TimeUnit.SECONDS),
                        "the source relay remains active while preparation is pending");
                fixture.disconnectTarget();
                assertEquals(TransferStatus.FAILED, transfer.get(5, TimeUnit.SECONDS).status());
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> fixture.sourceClosed.get(150, TimeUnit.MILLISECONDS));
                assertEquals("source", fixture.listener.find(player.identity()).flatMap(PlayerView::currentServer)
                        .orElseThrow());
                writeFrame(fixture.sourceOutput.get(), new byte[]{0x03, 0x66});
                assertArrayEquals(new byte[]{0x03, 0x66}, readFrame(client.input()));
                assertFalse(fixture.targetLogin.isDone());
            }
        }
    }

    @Test
    void candidateDeathAfterSourceReleaseDisconnectsTheClient() throws Exception {
        var acknowledgement = new CompletableFuture<Void>();
        var callbackEntered = new CompletableFuture<Void>();
        var cancelled = new CompletableFuture<Void>();
        acknowledgement.whenComplete((ignored, failure) -> {
            if (acknowledgement.isCancelled()) cancelled.complete(null);
        });
        try (TransferFixture fixture = new TransferFixture(preparation(releaseSource(
                () -> { callbackEntered.complete(null); return acknowledgement; })))) {
            try (Client client = fixture.login()) {
                PlayerView player = fixture.player();
                var transfer = fixture.listener.transfer(player.identity(), "target").toCompletableFuture();
                fixture.targetAccepted.get(5, TimeUnit.SECONDS);
                fixture.sourceClosed.get(5, TimeUnit.SECONDS);
                callbackEntered.get(5, TimeUnit.SECONDS);
                fixture.disconnectTarget();
                assertEquals(TransferStatus.FAILED, transfer.get(5, TimeUnit.SECONDS).status());
                assertEquals(-1, client.input().read());
                cancelled.get(5, TimeUnit.SECONDS);
                assertTrue(acknowledgement.isCancelled(), "candidate loss must cancel a pending release callback");
                assertFalse(fixture.targetLogin.isDone());
            }
        }
    }

    private static PreparedTransfer releaseSource(Supplier<CompletionStage<Void>> afterSourceClosed) {
        return new PreparedTransfer() {
            @Override public boolean requiresSourceRelease() { return true; }
            @Override public CompletionStage<Void> sourceClosed() {
                return afterSourceClosed.get();
            }
        };
    }

    private static TransferPreparation preparation(PreparedTransfer prepared) {
        return event -> CompletableFuture.completedFuture(prepared);
    }

    private static EventDispatcher dispatcher(TransferPreparation preparation) {
        return new EventDispatcher() {
            @Override public boolean hasSubscribers(Class<?> eventType) { return false; }
            @Override public <R> CompletionStage<R> dispatch(Event<R> event) {
                return CompletableFuture.completedFuture(null);
            }
            @Override public TransferPreparation selectTransferPreparation() { return preparation; }
        };
    }

    private static final class TransferFixture implements AutoCloseable {
        private final ServerSocket source = server();
        private final ServerSocket target = server();
        private final ServerSocket replacementTarget = server();
        private final CompletableFuture<Void> sourceClosed = new CompletableFuture<>();
        private final CompletableFuture<Void> targetAccepted = new CompletableFuture<>();
        private final CompletableFuture<String> targetLogin = new CompletableFuture<>();
        private final CompletableFuture<byte[]> targetFirstClientFrame = new CompletableFuture<>();
        private final LinkedBlockingQueue<byte[]> sourceClientFrames = new LinkedBlockingQueue<>();
        private final AtomicReference<DataOutputStream> sourceOutput = new AtomicReference<>();
        private final AtomicReference<Socket> targetSocket = new AtomicReference<>();
        private final ProxySessionListener listener;
        private final int proxyPort;
        private final InMemoryBackendCatalog catalog = new InMemoryBackendCatalog();

        private TransferFixture(TransferPreparation preparation) throws Exception {
            backendThread(source, this::serveSource);
            backendThread(target, this::serveTarget);
            register(catalog, "source", source);
            register(catalog, "target", target);
            listener = new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), catalog);
            listener.setPlacement(ignored -> CompletableFuture.completedFuture(Optional.of(
                    PlacementDecision.select("source"))));
            listener.setEvents(dispatcher(preparation), Duration.ofSeconds(5));
            proxyPort = ((InetSocketAddress) listener.start().toCompletableFuture()
                    .get(5, TimeUnit.SECONDS).localAddress()).getPort();
        }

        private Client login() throws Exception {
            Client client = new Client(proxyPort);
            client.login(USERNAME);
            assertEquals(2, packetId(readFrame(client.input())));
            assertEquals(1, packetId(readFrame(client.input())));
            assertEquals(8, packetId(readFrame(client.input())));
            player();
            return client;
        }

        private PlayerView player() throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                for (PlayerView player : listener.online()) if (player.username().equals(USERNAME)) return player;
                Thread.sleep(2);
            }
            throw new IOException("player did not complete login");
        }

        private void replaceTargetRegistration() {
            catalog.register(new BackendRegistration(new BackendId("target"), new BackendOwner("static", 0),
                    URI.create("tcp://127.0.0.1:" + replacementTarget.getLocalPort()), Map.of(), Map.of()));
        }

        private void disconnectTarget() throws Exception {
            targetAccepted.get(5, TimeUnit.SECONDS);
            Socket socket = targetSocket.get();
            if (socket == null) throw new IOException("target socket was not published");
            socket.close();
        }

        private void serveSource() {
            try (Socket socket = source.accept()) {
                socket.setSoTimeout(5000);
                DataInputStream input = new DataInputStream(socket.getInputStream());
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                acceptLogin(input, output, USERNAME, 42);
                sourceOutput.set(output);
                while (true) {
                    try { sourceClientFrames.add(readFrame(input)); }
                    catch (EOFException closed) { break; }
                }
                sourceClosed.complete(null);
            } catch (Throwable failure) { sourceClosed.completeExceptionally(failure); }
        }

        private void serveTarget() {
            try (Socket socket = target.accept()) {
                targetSocket.set(socket);
                socket.setSoTimeout(5000);
                targetAccepted.complete(null);
                DataInputStream input = new DataInputStream(socket.getInputStream());
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                readFrame(input); // Backend handshake.
                byte[] login = readFrame(input);
                ByteArrayInputStream loginData = new ByteArrayInputStream(login);
                assertEquals(0, readVarInt(loginData));
                String username = readString(loginData);
                targetLogin.complete(username);
                writeFrameBundle(output, loginSuccess(username), joinGame(99), new byte[]{0x08});
                byte[] frame = readFrame(input);
                targetFirstClientFrame.complete(frame);
                while (input.read() != -1) { }
            } catch (Throwable failure) {
                targetAccepted.completeExceptionally(failure);
            }
        }

        @Override public void close() throws Exception {
            try { listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS); }
            finally {
                source.close();
                target.close();
                replacementTarget.close();
            }
        }
    }

    private static final class Client implements AutoCloseable {
        private final Socket socket = new Socket();
        private final DataInputStream input;
        private final DataOutputStream output;
        private Client(int port) throws IOException {
            socket.setSoTimeout(5000);
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5000);
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(socket.getOutputStream());
        }
        private void login(String username) throws IOException { sendHandshakeAndLogin(output, username); }
        private DataInputStream input() { return input; }
        private DataOutputStream output() { return output; }
        private void setSoTimeout(int timeoutMillis) throws IOException { socket.setSoTimeout(timeoutMillis); }
        @Override public void close() throws IOException { socket.close(); }
    }

    private static ServerSocket server() throws IOException {
        return new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
    }

    private static void backendThread(ServerSocket socket, Runnable task) {
        Thread thread = new Thread(task, "source-release-test-backend-" + socket.getLocalPort());
        thread.setDaemon(true);
        thread.start();
    }

    private static void register(InMemoryBackendCatalog catalog, String name, ServerSocket socket) {
        catalog.register(new BackendRegistration(new BackendId(name), new BackendOwner("static", 0),
                URI.create("tcp://127.0.0.1:" + socket.getLocalPort()), Map.of(), Map.of()));
    }

    private static void acceptLogin(DataInputStream input, DataOutputStream output, String username, int entityId)
            throws Exception {
        readFrame(input); // Handshake.
        byte[] login = readFrame(input);
        ByteArrayInputStream loginData = new ByteArrayInputStream(login);
        assertEquals(0, readVarInt(loginData));
        assertEquals(username, readString(loginData));
        sendLoginSuccess(output, username);
        sendJoinGame(output, entityId);
        writeFrame(output, new byte[]{0x08});
    }

    private static void sendLoginSuccess(DataOutputStream output, String username) throws IOException {
        writeFrame(output, loginSuccess(username));
    }

    private static byte[] loginSuccess(String username) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeVarInt(body, 2);
        writeString(body, UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8)).toString());
        writeString(body, username);
        return body.toByteArray();
    }

    private static void sendJoinGame(DataOutputStream output, int entityId) throws IOException {
        writeFrame(output, joinGame(entityId));
    }

    private static byte[] joinGame(int entityId) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeVarInt(body, 1);
        DataOutputStream data = new DataOutputStream(body);
        data.writeInt(entityId); data.writeByte(0); data.writeByte(0); data.writeByte(0); data.writeByte(20);
        writeString(body, "default");
        return body.toByteArray();
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

    private static int packetId(byte[] frame) throws IOException { return readVarInt(new ByteArrayInputStream(frame)); }

    private static byte[] readFrame(DataInputStream input) throws IOException {
        int length = readVarInt(input);
        if (length < 1 || length > 1_048_576) throw new IOException("invalid frame length " + length);
        byte[] body = new byte[length]; input.readFully(body); return body;
    }

    private static void writeFrame(DataOutputStream output, byte[] body) throws IOException {
        writeVarInt(output, body.length); output.write(body); output.flush();
    }

    private static void writeFrameBundle(DataOutputStream output, byte[]... bodies) throws IOException {
        ByteArrayOutputStream bundle = new ByteArrayOutputStream();
        for (byte[] body : bodies) { writeVarInt(bundle, body.length); bundle.write(body); }
        output.write(bundle.toByteArray());
        output.flush();
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

    private static String readString(ByteArrayInputStream input) throws IOException {
        int length = readVarInt(input);
        if (length < 0 || length > 1024) throw new IOException("invalid string length");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(ByteArrayOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length); output.write(bytes);
    }
}

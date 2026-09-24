package dev.strataproxy.core.session;

import dev.strataproxy.api.PlacementDecision;
import dev.strataproxy.api.TransferStatus;
import dev.strataproxy.core.backend.BackendId;
import dev.strataproxy.core.backend.BackendHandle;
import dev.strataproxy.core.backend.BackendOwner;
import dev.strataproxy.core.backend.BackendRegistration;
import dev.strataproxy.core.backend.InMemoryBackendCatalog;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SessionTransferTest {
    private static final String USERNAME = "IslandPlayer";
    private static final UUID PLAYER_ID = UUID.nameUUIDFromBytes(
            ("OfflinePlayer:" + USERNAME).getBytes(StandardCharsets.UTF_8));

    @Test
    void transfersVanillaPlayConnectionWithoutSecondLoginAndReleasesOldCapacity() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        try (ServerSocket oldServer = server(); ServerSocket newServer = server()) {
            var oldClosed = new CompletableFuture<Void>();
            var newRelayed = new CompletableFuture<Void>();
            backendThread(oldServer, () -> {
                try (Socket socket = oldServer.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    acceptLogin(input, output, 0);
                    if (input.read() != -1) throw new AssertionError("old backend received bytes after transfer");
                    oldClosed.complete(null);
                } catch (Throwable failure) { oldClosed.completeExceptionally(failure); }
            });
            backendThread(newServer, () -> {
                try (Socket socket = newServer.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    acceptLogin(input, output, 0);
                    assertArrayEquals(new byte[]{0x01, 0x33}, readFrame(input));
                    writeFrame(output, new byte[]{0x03, 0x44});
                    newRelayed.complete(null);
                    while (input.read() != -1) { }
                } catch (Throwable failure) { newRelayed.completeExceptionally(failure); }
            });
            var oldHandle = register(catalog, "old", oldServer);
            var newHandle = register(catalog, "new", newServer);
            var listener = listener(catalog);
            try {
                InetSocketAddress bound = (InetSocketAddress) listener.start().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).localAddress();
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(), bound.getPort())) {
                    client.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(client.getInputStream());
                    DataOutputStream output = new DataOutputStream(client.getOutputStream());
                    sendLogin(output);
                    assertEquals(2, packetId(readFrame(input))); // One login success for the whole client session.
                    assertEquals(1, packetId(readFrame(input))); // Initial Join Game.
                    assertEquals(8, packetId(readFrame(input))); // Initial Position and Look.
                    var player = awaitPlayer(listener);
                    assertEquals("old", player.currentServer().orElseThrow());
                    assertEquals(TransferStatus.NETWORK_READY,
                            listener.transfer(player.identity(), "new").toCompletableFuture()
                                    .get(5, TimeUnit.SECONDS).status());
                    assertEquals(7, packetId(readFrame(input))); // Dummy respawn for same dimension.
                    assertEquals(7, packetId(readFrame(input))); // Target dimension.
                    assertEquals(8, packetId(readFrame(input))); // Target Position and Look, no second Login Success.
                    writeFrame(output, new byte[]{0x01, 0x33});
                    assertArrayEquals(new byte[]{0x03, 0x44}, readFrame(input));
                    newRelayed.get(5, TimeUnit.SECONDS);
                    oldClosed.get(5, TimeUnit.SECONDS);
                    assertEquals("new", listener.find(player.identity()).orElseThrow().currentServer().orElseThrow());
                    assertEquals(1, catalog.find(oldHandle.id()).orElseThrow().availableUnits());
                    assertEquals(0, catalog.find(newHandle.id()).orElseThrow().availableUnits());
                }
            } finally { listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS); }
        }
    }

    @Test
    void failedCandidateLoginKeepsOldBackendUsableAndReleasesCandidateCapacity() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        try (ServerSocket oldServer = server(); ServerSocket newServer = server()) {
            var oldRelayed = new CompletableFuture<Void>();
            var newClosed = new CompletableFuture<Void>();
            backendThread(oldServer, () -> {
                try (Socket socket = oldServer.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    acceptLogin(input, output, 0);
                    assertArrayEquals(new byte[]{0x01, 0x55}, readFrame(input));
                    writeFrame(output, new byte[]{0x03, 0x66});
                    oldRelayed.complete(null);
                } catch (Throwable failure) { oldRelayed.completeExceptionally(failure); }
            });
            backendThread(newServer, () -> {
                try (Socket socket = newServer.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    readFrame(input);
                    readFrame(input);
                    newClosed.complete(null);
                } catch (Throwable failure) { newClosed.completeExceptionally(failure); }
            });
            var oldHandle = register(catalog, "old", oldServer);
            var newHandle = register(catalog, "new", newServer);
            var listener = listener(catalog);
            try {
                InetSocketAddress bound = (InetSocketAddress) listener.start().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).localAddress();
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(), bound.getPort())) {
                    client.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(client.getInputStream());
                    DataOutputStream output = new DataOutputStream(client.getOutputStream());
                    sendLogin(output);
                    readFrame(input); readFrame(input); readFrame(input);
                    var player = awaitPlayer(listener);
                    var result = listener.transfer(player.identity(), "new").toCompletableFuture()
                            .get(5, TimeUnit.SECONDS);
                    assertEquals(TransferStatus.FAILED, result.status());
                    newClosed.get(5, TimeUnit.SECONDS);
                    assertEquals("old", listener.find(player.identity()).orElseThrow().currentServer().orElseThrow());
                    assertEquals(0, catalog.find(oldHandle.id()).orElseThrow().availableUnits());
                    assertEquals(1, catalog.find(newHandle.id()).orElseThrow().availableUnits());
                    writeFrame(output, new byte[]{0x01, 0x55});
                    assertArrayEquals(new byte[]{0x03, 0x66}, readFrame(input));
                    oldRelayed.get(5, TimeUnit.SECONDS);
                }
            } finally { listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS); }
        }
    }

    @Test
    void forgeTransferResetsHandshakeAndUsesServerHelloDimensionOverride() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        try (ServerSocket oldServer = server(); ServerSocket newServer = server()) {
            var oldClosed = new CompletableFuture<Void>();
            var newNegotiated = new CompletableFuture<Void>();
            backendThread(oldServer, () -> {
                try (Socket socket = oldServer.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    acceptForgeLogin(input, output, 5);
                    assertArrayEquals(clientForgeAck(), readFrame(input));
                    if (input.read() != -1) throw new AssertionError("old Forge backend received post-transfer data");
                    oldClosed.complete(null);
                } catch (Throwable failure) { oldClosed.completeExceptionally(failure); }
            });
            backendThread(newServer, () -> {
                try (Socket socket = newServer.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    acceptForgeLogin(input, output, 7);
                    assertArrayEquals(clientForgeAck(), readFrame(input));
                    writeFrame(output, new byte[]{0x03, 0x44});
                    newNegotiated.complete(null);
                    while (input.read() != -1) { }
                } catch (Throwable failure) { newNegotiated.completeExceptionally(failure); }
            });
            register(catalog, "old", oldServer);
            register(catalog, "new", newServer);
            var listener = listener(catalog);
            try {
                InetSocketAddress bound = (InetSocketAddress) listener.start().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).localAddress();
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(), bound.getPort())) {
                    client.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(client.getInputStream());
                    DataOutputStream output = new DataOutputStream(client.getOutputStream());
                    sendLogin(output);
                    assertEquals(2, packetId(readFrame(input)));
                    assertEquals(1, packetId(readFrame(input)));
                    assertArrayEquals(serverForgeHello(5), readFrame(input));
                    assertArrayEquals(serverForgeAck(), readFrame(input));
                    writeFrame(output, clientForgeAck());
                    var player = awaitPlayer(listener);
                    assertEquals(TransferStatus.NETWORK_READY,
                            listener.transfer(player.identity(), "new").toCompletableFuture()
                                    .get(5, TimeUnit.SECONDS).status());
                    byte[] reset = readFrame(input);
                    assertEquals(0x3f, packetId(reset));
                    assertEquals((byte) 0xfe, reset[reset.length - 1]);
                    byte[] respawn = readFrame(input);
                    var respawnInput = new DataInputStream(new ByteArrayInputStream(respawn));
                    assertEquals(7, readVarInt(respawnInput));
                    assertEquals(7, respawnInput.readInt()); // Target override, not Join Game's signed byte.
                    assertArrayEquals(serverForgeHello(7), readFrame(input));
                    assertArrayEquals(serverForgeAck(), readFrame(input));
                    writeFrame(output, clientForgeAck());
                    assertArrayEquals(new byte[]{0x03, 0x44}, readFrame(input));
                    newNegotiated.get(5, TimeUnit.SECONDS);
                    oldClosed.get(5, TimeUnit.SECONDS);
                }
            } finally { listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS); }
        }
    }

    @Test
    void clientDisconnectDuringCandidateLoginCompletesTransferAndReleasesBothReservations() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        try (ServerSocket oldServer = server(); ServerSocket newServer = server()) {
            var oldClosed = new CompletableFuture<Void>();
            var candidateAccepted = new CompletableFuture<Void>();
            backendThread(oldServer, () -> {
                try (Socket socket = oldServer.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    acceptLogin(input, output, 0);
                    if (input.read() != -1) throw new AssertionError("unexpected old backend data");
                    oldClosed.complete(null);
                } catch (Throwable failure) { oldClosed.completeExceptionally(failure); }
            });
            backendThread(newServer, () -> {
                try (Socket socket = newServer.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    readFrame(input); readFrame(input);
                    candidateAccepted.complete(null);
                    if (input.read() != -1) throw new AssertionError("unexpected candidate data");
                } catch (Throwable failure) { candidateAccepted.completeExceptionally(failure); }
            });
            var oldHandle = register(catalog, "old", oldServer);
            var newHandle = register(catalog, "new", newServer);
            var listener = listener(catalog);
            try {
                InetSocketAddress bound = (InetSocketAddress) listener.start().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).localAddress();
                Socket client = new Socket(InetAddress.getLoopbackAddress(), bound.getPort());
                try {
                    client.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(client.getInputStream());
                    sendLogin(new DataOutputStream(client.getOutputStream()));
                    readFrame(input); readFrame(input); readFrame(input);
                    var player = awaitPlayer(listener);
                    var transfer = listener.transfer(player.identity(), "new").toCompletableFuture();
                    candidateAccepted.get(5, TimeUnit.SECONDS);
                    client.close();
                    assertEquals(TransferStatus.FAILED, transfer.get(5, TimeUnit.SECONDS).status());
                    oldClosed.get(5, TimeUnit.SECONDS);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!listener.online().isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
                    assertTrue(listener.online().isEmpty());
                    assertEquals(1, catalog.find(oldHandle.id()).orElseThrow().availableUnits());
                    assertEquals(1, catalog.find(newHandle.id()).orElseThrow().availableUnits());
                } finally { client.close(); }
            } finally { listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS); }
        }
    }

    private static ProxySessionListener listener(InMemoryBackendCatalog catalog) {
        var listener = new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), catalog);
        listener.setPlacement(ignored -> CompletableFuture.completedFuture(Optional.of(PlacementDecision.select("old"))));
        return listener;
    }

    private static dev.strataproxy.api.PlayerView awaitPlayer(ProxySessionListener listener) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (listener.online().isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(1, listener.online().size());
        Thread.sleep(20); // Let PLAY frames observed by the proxy relay complete on its event loop.
        return listener.online().get(0);
    }

    private static BackendHandle register(InMemoryBackendCatalog catalog,
                                                              String name, ServerSocket socket) {
        return catalog.register(new BackendRegistration(new BackendId(name), new BackendOwner("static", 0),
                URI.create("tcp://127.0.0.1:" + socket.getLocalPort()), 1, Map.of(), Map.of())).handle();
    }

    private static void acceptLogin(DataInputStream input, DataOutputStream output, int dimension) throws Exception {
        assertEquals(0, packetId(readFrame(input)));
        assertEquals(0, packetId(readFrame(input)));
        ByteArrayOutputStream success = new ByteArrayOutputStream();
        var successData = new DataOutputStream(success);
        writeVarInt(successData, 2);
        writeString(successData, PLAYER_ID.toString());
        writeString(successData, USERNAME);
        writeFrame(output, success.toByteArray());
        ByteArrayOutputStream join = new ByteArrayOutputStream();
        var joinData = new DataOutputStream(join);
        writeVarInt(joinData, 1);
        joinData.writeInt(100);
        joinData.writeByte(0);
        joinData.writeByte(dimension);
        joinData.writeByte(1);
        joinData.writeByte(20);
        writeString(joinData, "default");
        writeFrame(output, join.toByteArray());
        writeFrame(output, new byte[]{0x08});
    }

    private static void acceptForgeLogin(DataInputStream input, DataOutputStream output, int dimensionOverride)
            throws Exception {
        assertEquals(0, packetId(readFrame(input)));
        assertEquals(0, packetId(readFrame(input)));
        ByteArrayOutputStream success = new ByteArrayOutputStream();
        var successData = new DataOutputStream(success);
        writeVarInt(successData, 2);
        writeString(successData, PLAYER_ID.toString());
        writeString(successData, USERNAME);
        writeFrame(output, success.toByteArray());
        ByteArrayOutputStream join = new ByteArrayOutputStream();
        var joinData = new DataOutputStream(join);
        writeVarInt(joinData, 1);
        joinData.writeInt(100);
        joinData.writeByte(0);
        joinData.writeByte(0);
        joinData.writeByte(1);
        joinData.writeByte(20);
        writeString(joinData, "default");
        writeFrame(output, join.toByteArray());
        writeFrame(output, serverForgeHello(dimensionOverride));
        writeFrame(output, serverForgeAck());
    }

    private static byte[] serverForgeHello(int dimensionOverride) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        var output = new DataOutputStream(payload);
        writeVarInt(output, 0x3f);
        writeString(output, "FML|HS");
        output.writeShort(6);
        output.writeByte(0);
        output.writeByte(2);
        output.writeInt(dimensionOverride);
        return payload.toByteArray();
    }

    private static byte[] serverForgeAck() throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        var output = new DataOutputStream(payload);
        writeVarInt(output, 0x3f);
        writeString(output, "FML|HS");
        output.writeShort(2);
        output.writeByte(0xff);
        output.writeByte(3);
        return payload.toByteArray();
    }

    private static byte[] clientForgeAck() throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        var output = new DataOutputStream(payload);
        writeVarInt(output, 0x17);
        writeString(output, "FML|HS");
        output.writeShort(2);
        output.writeByte(0xff);
        output.writeByte(5);
        return payload.toByteArray();
    }

    private static void sendLogin(DataOutputStream output) throws Exception {
        ByteArrayOutputStream handshake = new ByteArrayOutputStream();
        var hello = new DataOutputStream(handshake);
        writeVarInt(hello, 0); writeVarInt(hello, 5); writeString(hello, "localhost");
        hello.writeShort(25565); writeVarInt(hello, 2);
        writeFrame(output, handshake.toByteArray());
        ByteArrayOutputStream login = new ByteArrayOutputStream();
        var start = new DataOutputStream(login);
        writeVarInt(start, 0); writeString(start, USERNAME);
        writeFrame(output, login.toByteArray());
    }

    private static ServerSocket server() throws Exception {
        return new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
    }

    private static void backendThread(ServerSocket socket, Runnable task) {
        Thread thread = new Thread(task, "transfer-test-backend-" + socket.getLocalPort());
        thread.setDaemon(true);
        thread.start();
    }

    private static int packetId(byte[] bytes) throws Exception {
        return readVarInt(new ByteArrayInputStream(bytes));
    }

    private static byte[] readFrame(DataInputStream input) throws Exception {
        int length = readVarInt(input);
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException();
        return bytes;
    }

    private static int readVarInt(InputStream input) throws Exception {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int next = input.read();
            if (next < 0) throw new EOFException();
            value |= (next & 0x7f) << shift;
            if ((next & 0x80) == 0) return value;
        }
        throw new IllegalArgumentException("oversized VarInt");
    }

    private static void writeFrame(DataOutputStream output, byte[] bytes) throws Exception {
        writeVarInt(output, bytes.length);
        output.write(bytes);
        output.flush();
    }

    private static void writeString(DataOutputStream output, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length);
        output.write(bytes);
    }

    private static void writeVarInt(DataOutputStream output, int value) throws Exception {
        do {
            int part = value & 0x7f;
            value >>>= 7;
            output.writeByte(value == 0 ? part : part | 0x80);
        } while (value != 0);
    }
}

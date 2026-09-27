package dev.moonbridge.core.session;

import dev.moonbridge.api.AccessDecision;
import dev.moonbridge.api.PlacementDecision;
import dev.moonbridge.api.PlayerView;
import dev.moonbridge.api.event.Event;
import dev.moonbridge.api.event.PlayerAdmissionEvent;
import dev.moonbridge.api.permission.PermissionContext;
import dev.moonbridge.api.permission.PermissionDecision;
import dev.moonbridge.api.permission.PermissionSubject;
import dev.moonbridge.api.permission.PermissionResult;
import dev.moonbridge.core.backend.BackendId;
import dev.moonbridge.core.backend.BackendOwner;
import dev.moonbridge.core.backend.BackendRegistration;
import dev.moonbridge.core.backend.InMemoryBackendCatalog;
import dev.moonbridge.core.event.EventDispatcher;
import dev.moonbridge.core.permission.PermissionService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PermissionLifecycleTest {
    private static final String USERNAME = "PermissionGate";
    private static final UUID PLAYER_ID = UUID.nameUUIDFromBytes(
            ("OfflinePlayer:" + USERNAME).getBytes(StandardCharsets.UTF_8));

    @Test
    void permissionLoadCompletesBeforeAdmissionPlacementOrBackendDial() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        try (var backend = serverSocket()) {
            register(catalog, "lobby", backend.getLocalPort());
            var opening = new CompletableFuture<PermissionSubject>();
            var providerCalled = new CountDownLatch(1);
            var admissionCalls = new AtomicInteger();
            var placementCalls = new AtomicInteger();
            var subject = new ProbeSubject();
            var service = new PermissionService(Duration.ofSeconds(3));
            service.configure(player -> {
                providerCalled.countDown();
                return opening;
            });
            var listener = listener(catalog);
            listener.setPermissions(service);
            listener.setInitialServers(java.util.List.of("lobby"));
            listener.setPlacement(player -> {
                placementCalls.incrementAndGet();
                return CompletableFuture.completedFuture(Optional.empty());
            });
            listener.setEvents(admissionEvents(admissionCalls, service), Duration.ofSeconds(2));
            try {
                int port = start(listener);
                try (Socket client = client(port)) {
                    sendLogin(client);
                    assertTrue(providerCalled.await(3, TimeUnit.SECONDS), "permission provider must be invoked");
                    assertEquals(0, admissionCalls.get(), "player admission must wait for permission loading");
                    assertEquals(0, placementCalls.get(), "initial server selection must wait for permission loading");
                    assertNoBackendDial(backend);

                    opening.complete(subject);
                    try (Socket upstream = backend.accept()) {
                        upstream.setSoTimeout(5000);
                        var backendInput = new DataInputStream(upstream.getInputStream());
                        readFrame(backendInput); // Handshake
                        readFrame(backendInput); // Login Start
                        writeFrame(new DataOutputStream(upstream.getOutputStream()), loginSuccess());
                        assertEquals(2, readVarInt(new ByteArrayInputStream(
                                readFrame(new DataInputStream(client.getInputStream())))));
                    }
                    assertEquals(1, admissionCalls.get());
                    assertEquals(1, placementCalls.get());
                }
            } finally {
                listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
                service.close();
            }
            assertTrue(subject.closed.await(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedPermissionLoadRejectsBeforeLoginAdmissionPlacementAndRouting() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        try (var backend = serverSocket()) {
            register(catalog, "lobby", backend.getLocalPort());
            var admissionCalls = new AtomicInteger();
            var placementCalls = new AtomicInteger();
            var service = new PermissionService(Duration.ofSeconds(2));
            service.configure(player -> CompletableFuture.failedFuture(new IllegalStateException("permission store offline")));
            var listener = listener(catalog);
            listener.setPermissions(service);
            listener.setInitialServers(java.util.List.of("lobby"));
            listener.setPlacement(player -> {
                placementCalls.incrementAndGet();
                return CompletableFuture.completedFuture(Optional.of(PlacementDecision.reject("must not run")));
            });
            listener.setEvents(admissionEvents(admissionCalls, service), Duration.ofSeconds(2));
            try {
                try (Socket client = client(start(listener))) {
                    sendLogin(client);
                    assertEquals("Could not load permissions. Please try again.", readLoginDisconnect(client));
                    assertEquals(0, admissionCalls.get());
                    assertEquals(0, placementCalls.get());
                    assertNoBackendDial(backend);
                }
            } finally {
                listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
                service.close();
            }
        }
    }

    private static EventDispatcher admissionEvents(AtomicInteger calls, PermissionService permissions) {
        return new EventDispatcher() {
            @Override public boolean hasSubscribers(Class<?> eventType) {
                return eventType == PlayerAdmissionEvent.class;
            }

            @Override @SuppressWarnings("unchecked")
            public <R> java.util.concurrent.CompletionStage<R> dispatch(Event<R> event) {
                if (!(event instanceof PlayerAdmissionEvent admission)) throw new AssertionError("unexpected event " + event);
                assertEquals(PermissionResult.ALLOW,
                        permissions.check(admission.player().identity(), "moonbridge.lifecycle.ready"),
                        "permission state must be usable before player admission listeners run");
                calls.incrementAndGet();
                return (java.util.concurrent.CompletionStage<R>) CompletableFuture.completedFuture(AccessDecision.allow());
            }
        };
    }

    private static ProxySessionListener listener(InMemoryBackendCatalog catalog) {
        return new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), catalog);
    }

    private static int start(ProxySessionListener listener) throws Exception {
        return ((InetSocketAddress) listener.start().toCompletableFuture()
                .get(5, TimeUnit.SECONDS).localAddress()).getPort();
    }

    private static ServerSocket serverSocket() throws IOException {
        var socket = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        socket.setSoTimeout(5000);
        return socket;
    }

    private static Socket client(int port) throws IOException {
        var socket = new Socket(InetAddress.getLoopbackAddress(), port);
        socket.setSoTimeout(5000);
        return socket;
    }

    private static void register(InMemoryBackendCatalog catalog, String id, int port) {
        catalog.register(new BackendRegistration(new BackendId(id), new BackendOwner("static", 0),
                URI.create("tcp://127.0.0.1:" + port), Map.of(), Map.of()));
    }

    private static void assertNoBackendDial(ServerSocket backend) throws Exception {
        int previousTimeout = backend.getSoTimeout();
        backend.setSoTimeout(150);
        try {
            assertThrows(SocketTimeoutException.class, backend::accept,
                    "backend must receive no connection before permission loading completes");
        } finally {
            backend.setSoTimeout(previousTimeout);
        }
    }

    private static void sendLogin(Socket client) throws IOException {
        var handshake = new ByteArrayOutputStream();
        writeVarInt(handshake, 0);
        writeVarInt(handshake, 5);
        writeString(handshake, "localhost");
        handshake.write(0x63);
        handshake.write(0xdd);
        writeVarInt(handshake, 2);
        writeFrame(client.getOutputStream(), handshake.toByteArray());
        var login = new ByteArrayOutputStream();
        writeVarInt(login, 0);
        writeString(login, USERNAME);
        writeFrame(client.getOutputStream(), login.toByteArray());
    }

    private static String readLoginDisconnect(Socket client) throws Exception {
        var input = new ByteArrayInputStream(readFrame(new DataInputStream(client.getInputStream())));
        assertEquals(0, readVarInt(input));
        String json = readString(input);
        String reason = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).path("text").asText();
        assertEquals(-1, client.getInputStream().read());
        return reason;
    }

    private static byte[] loginSuccess() throws IOException {
        var success = new ByteArrayOutputStream();
        writeVarInt(success, 2);
        writeString(success, PLAYER_ID.toString());
        writeString(success, USERNAME);
        return success.toByteArray();
    }

    private static byte[] readFrame(DataInputStream input) throws IOException {
        int length = readVarInt(input);
        byte[] frame = new byte[length];
        input.readFully(frame);
        return frame;
    }

    private static void writeFrame(DataOutputStream output, byte[] body) throws IOException {
        writeVarInt(output, body.length);
        output.write(body);
        output.flush();
    }

    private static void writeFrame(java.io.OutputStream output, byte[] body) throws IOException {
        writeVarInt(output, body.length);
        output.write(body);
        output.flush();
    }

    private static int readVarInt(DataInputStream input) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int next = input.readUnsignedByte();
            value |= (next & 0x7f) << shift;
            if ((next & 0x80) == 0) return value;
        }
        throw new IOException("invalid VarInt");
    }

    private static int readVarInt(ByteArrayInputStream input) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int next = input.read();
            if (next < 0) throw new IOException("unexpected end of VarInt");
            value |= (next & 0x7f) << shift;
            if ((next & 0x80) == 0) return value;
        }
        throw new IOException("invalid VarInt");
    }

    private static void writeVarInt(ByteArrayOutputStream output, int value) {
        while ((value & ~0x7f) != 0) {
            output.write((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        output.write(value);
    }

    private static void writeVarInt(java.io.OutputStream output, int value) throws IOException {
        while ((value & ~0x7f) != 0) {
            output.write((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        output.write(value);
    }

    private static void writeString(ByteArrayOutputStream output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length);
        output.writeBytes(bytes);
    }

    private static String readString(ByteArrayInputStream input) throws IOException {
        int length = readVarInt(input);
        if (length < 0 || length > input.available()) throw new IOException("invalid string length " + length);
        return new String(input.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static class ProbeSubject implements PermissionSubject {
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override public PermissionDecision check(String node, PermissionContext context) {
            return PermissionDecision.ALLOW;
        }

        @Override public void update(PlayerView player) { }

        @Override public void close() { closed.countDown(); }
    }
}

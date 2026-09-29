package dev.moonbridge.core.session;

import dev.moonbridge.api.PlacementDecision;
import dev.moonbridge.api.profile.PreparedProfile;
import dev.moonbridge.api.profile.ProfileHandoffCoordinator;
import dev.moonbridge.api.profile.ProfileHandoffRequest;
import dev.moonbridge.api.profile.ProfileSession;
import dev.moonbridge.api.profile.ProfileState;
import dev.moonbridge.api.profile.SourceInputBarrier;
import dev.moonbridge.core.backend.BackendId;
import dev.moonbridge.core.backend.BackendOwner;
import dev.moonbridge.core.backend.BackendRegistration;
import dev.moonbridge.core.backend.InMemoryBackendCatalog;
import org.junit.jupiter.api.Test;

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
import java.net.UnknownHostException;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InitialRoutingTest {
    private static final String USERNAME = "RouteProbe";
    private static final UUID PLAYER_ID = UUID.nameUUIDFromBytes(
            ("OfflinePlayer:" + USERNAME).getBytes(StandardCharsets.UTF_8));

    @Test
    void configuredCandidateOrderWinsOverRegistrationOrderAndTcpFailureAdvances() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        int refusedPort;
        try (var refused = serverSocket()) { refusedPort = refused.getLocalPort(); }
        try (var healthy = serverSocket(); var firstRegistered = serverSocket()) {
            // Registration order deliberately differs from route order. A registered backend is not
            // implicitly preferred merely because it appeared first in the catalog.
            register(catalog, "first-registered", firstRegistered.getLocalPort());
            register(catalog, "healthy", healthy.getLocalPort());
            register(catalog, "down", refusedPort);
            var listener = listener(catalog);
            listener.setPlacement(player -> CompletableFuture.completedFuture(Optional.empty()));
            listener.setInitialServers(List.of("down", "healthy"));
            try {
                int proxyPort = start(listener);
                try (Socket client = client(proxyPort)) {
                    sendLogin(client);
                    try (Socket backend = healthy.accept()) {
                        backend.setSoTimeout(5000);
                        var input = new DataInputStream(backend.getInputStream());
                        assertEquals(0, readVarInt(readFrame(input))); // Handshake
                        assertEquals(0, readVarInt(readFrame(input))); // Login Start
                        writeFrame(new DataOutputStream(backend.getOutputStream()), loginSuccess());
                        assertEquals(2, readVarInt(readFrame(new DataInputStream(client.getInputStream()))));
                    }
                    assertNoConnection(firstRegistered);
                }
            } finally {
                listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void emptyConfiguredEntryListFailsClosedEvenWhenCatalogHasBackends() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        try (var registered = serverSocket()) {
            register(catalog, "registered-but-not-entry", registered.getLocalPort());
            var listener = listener(catalog);
            listener.setPlacement(player -> CompletableFuture.completedFuture(Optional.empty()));
            listener.setInitialServers(List.of());
            try {
                int proxyPort = start(listener);
                try (Socket client = client(proxyPort)) {
                    sendLogin(client);
                    assertLoginDisconnect(client, "No entry servers are configured.");
                    assertNoConnection(registered);
                }
            } finally {
                listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void profileAdmissionDenialPreventsAnyBackendDial() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        try (var backend = serverSocket()) {
            register(catalog, "lobby", backend.getLocalPort());
            var listener = listener(catalog);
            listener.setPlacement(player -> CompletableFuture.completedFuture(Optional.empty()));
            listener.setInitialServers(List.of("lobby"));
            listener.setProfileHandoffs(true, new ProfileHandoffCoordinator() {
                @Override public CompletableFuture<PreparedProfile> prepareAdmission(ProfileHandoffRequest request) {
                    return CompletableFuture.failedFuture(new IllegalStateException("profile store unavailable"));
                }
                @Override public CompletableFuture<PreparedProfile> prepareTransfer(
                        ProfileHandoffRequest request, SourceInputBarrier barrier) {
                    return CompletableFuture.failedFuture(new AssertionError("not a transfer"));
                }
                @Override public CompletableFuture<ProfileState> queryActive(ProfileSession session) {
                    return CompletableFuture.completedFuture(ProfileState.UNKNOWN);
                }
            });
            try {
                int proxyPort = start(listener);
                try (Socket client = client(proxyPort)) {
                    sendLogin(client);
                    assertLoginDisconnect(client, "Player profile is not ready.");
                    assertNoConnection(backend);
                }
            } finally {
                listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void staleAdmissionCompletionIsAbortedWithoutDialingItsBackend() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        try (var backend = serverSocket()) {
            register(catalog, "lobby", backend.getLocalPort());
            var ready = new CompletableFuture<PreparedProfile>();
            var requested = new CompletableFuture<ProfileHandoffRequest>();
            var aborted = new AtomicInteger();
            var listener = listener(catalog);
            listener.setPlacement(player -> CompletableFuture.completedFuture(Optional.empty()));
            listener.setInitialServers(List.of("lobby"));
            listener.setProfileHandoffs(true, new ProfileHandoffCoordinator() {
                @Override public CompletableFuture<PreparedProfile> prepareAdmission(ProfileHandoffRequest request) {
                    requested.complete(request);
                    return ready;
                }
                @Override public CompletableFuture<PreparedProfile> prepareTransfer(
                        ProfileHandoffRequest request, SourceInputBarrier barrier) {
                    return CompletableFuture.failedFuture(new AssertionError("not a transfer"));
                }
                @Override public CompletableFuture<ProfileState> queryActive(ProfileSession session) {
                    return CompletableFuture.completedFuture(ProfileState.UNKNOWN);
                }
            });
            try {
                int proxyPort = start(listener);
                Socket client = client(proxyPort);
                sendLogin(client);
                var request = requested.get(5, TimeUnit.SECONDS);
                assertEquals(PLAYER_ID, request.player().playerId());
                assertTrue(!request.operationId().equals(request.attemptId()));
                client.close();
                awaitSessionsReleased(listener);
                ready.complete(new PreparedProfile() {
                    @Override public CompletableFuture<Void> routed() {
                        return CompletableFuture.failedFuture(new AssertionError("stale route"));
                    }
                    @Override public CompletableFuture<Void> abort() {
                        aborted.incrementAndGet();
                        return CompletableFuture.completedFuture(null);
                    }
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (aborted.get() == 0 && System.nanoTime() < deadline) Thread.sleep(5);
                assertEquals(1, aborted.get());
                assertNoConnection(backend);
            } finally {
                listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void explicitPluginRouteOverridesConfiguredEntryServer() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        try (var configuredDefault = serverSocket(); var pluginTarget = serverSocket()) {
            register(catalog, "default", configuredDefault.getLocalPort());
            register(catalog, "plugin-target", pluginTarget.getLocalPort());
            var listener = listener(catalog);
            listener.setInitialServers(List.of("default"));
            listener.setPlacement(player -> CompletableFuture.completedFuture(
                    Optional.of(PlacementDecision.select("plugin-target"))));
            try {
                int proxyPort = start(listener);
                try (Socket client = client(proxyPort)) {
                    sendLogin(client);
                    try (Socket backend = pluginTarget.accept()) {
                        backend.setSoTimeout(5000);
                        var input = new DataInputStream(backend.getInputStream());
                        assertEquals(0, readVarInt(readFrame(input)));
                        assertEquals(0, readVarInt(readFrame(input)));
                        writeFrame(new DataOutputStream(backend.getOutputStream()), loginSuccess());
                        assertEquals(2, readVarInt(readFrame(new DataInputStream(client.getInputStream()))));
                    }
                    assertNoConnection(configuredDefault);
                }
            } finally {
                listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void pluginRejectFailureAndUnknownTargetNeverFallBackToConfiguredEntry() throws Exception {
        assertPlacementDoesNotUseDefault(Optional.of(PlacementDecision.reject("maintenance")),
                null, "maintenance");
        assertPlacementDoesNotUseDefault(null, new IOException("selector failed"),
                "Could not select a server. Please try again.");
        assertPlacementDoesNotUseDefault(Optional.of(PlacementDecision.select("not-registered")),
                null, "No entry server could be reached.");
    }

    @Test
    void backendLoginRejectionDoesNotAdvanceToAnotherCandidate() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        try (var rejecting = serverSocket(); var later = serverSocket()) {
            register(catalog, "rejecting", rejecting.getLocalPort());
            register(catalog, "later", later.getLocalPort());
            var listener = listener(catalog);
            listener.setPlacement(player -> CompletableFuture.completedFuture(Optional.of(
                    PlacementDecision.select(List.of("rejecting", "later")))));
            try {
                int proxyPort = start(listener);
                try (Socket client = client(proxyPort)) {
                    sendLogin(client);
                    try (Socket backend = rejecting.accept()) {
                        backend.setSoTimeout(5000);
                        var input = new DataInputStream(backend.getInputStream());
                        assertEquals(0, readVarInt(readFrame(input)));
                        assertEquals(0, readVarInt(readFrame(input)));
                        var rejection = new ByteArrayOutputStream();
                        writeVarInt(rejection, 0);
                        writeString(rejection, "{\"text\":\"Backend refused login\"}");
                        writeFrame(new DataOutputStream(backend.getOutputStream()), rejection.toByteArray());
                        var disconnect = new java.io.ByteArrayInputStream(
                                readFrame(new DataInputStream(client.getInputStream())));
                        assertEquals(0, readVarInt(disconnect));
                        assertEquals("Backend refused login", readJsonText(readString(disconnect, 32767)));
                    }
                    assertNoConnection(later);
                }
            } finally {
                listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void delayedDnsFailureAfterClientDisconnectDoesNotDialNextCandidate() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        try (var later = serverSocket()) {
            register(catalog, "later", later.getLocalPort());
            register(catalog, "dns", 25565, "backend.test");
            var resolver = new DeferredBackendResolver("backend.test");
            var listener = listener(catalog, resolver, Duration.ofSeconds(3));
            listener.setPlacement(player -> CompletableFuture.completedFuture(Optional.of(
                    PlacementDecision.select(List.of("dns", "later")))));
            try {
                int proxyPort = start(listener);
                try (Socket client = client(proxyPort)) {
                    sendLogin(client);
                    var pending = resolver.pending().get(5, TimeUnit.SECONDS);
                    client.close();
                    awaitSessionsReleased(listener);
                    pending.promise().tryFailure(new UnknownHostException("delayed test DNS failure"));
                    assertNoConnection(later);
                }
            } finally {
                listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void replacedBackendHandleIsClosedBeforeMinecraftBytesAndNextCandidateIsTried() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        try (var oldTarget = serverSocket(); var newTarget = serverSocket(); var healthy = serverSocket()) {
            register(catalog, "moving", oldTarget.getLocalPort(), "backend.test");
            register(catalog, "healthy", healthy.getLocalPort());
            var resolver = new DeferredBackendResolver("backend.test");
            var listener = listener(catalog, resolver, Duration.ofSeconds(5));
            listener.setPlacement(player -> CompletableFuture.completedFuture(Optional.of(
                    PlacementDecision.select(List.of("moving", "healthy")))));
            try {
                int proxyPort = start(listener);
                try (Socket client = client(proxyPort)) {
                    sendLogin(client);
                    var pending = resolver.pending().get(5, TimeUnit.SECONDS);
                    // Re-registering the name creates a new generation while the first DNS lookup
                    // is outstanding. The resolved old address must never receive a handshake.
                    register(catalog, "moving", newTarget.getLocalPort(), "backend.test");
                    pending.release();
                    try (Socket stale = oldTarget.accept()) {
                        stale.setSoTimeout(5000);
                        assertEquals(-1, stale.getInputStream().read(),
                                "stale candidate connection must close before any Minecraft bytes");
                    }
                    try (Socket backend = healthy.accept()) {
                        backend.setSoTimeout(5000);
                        var input = new DataInputStream(backend.getInputStream());
                        assertEquals(0, readVarInt(readFrame(input)));
                        assertEquals(0, readVarInt(readFrame(input)));
                        writeFrame(new DataOutputStream(backend.getOutputStream()), loginSuccess());
                        assertEquals(2, readVarInt(readFrame(new DataInputStream(client.getInputStream()))));
                    }
                    assertNoConnection(newTarget);
                }
            } finally {
                listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void totalRoutingTimeoutIncludesPendingAsynchronousPlacement() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        try (var later = serverSocket()) {
            register(catalog, "dns", 25565, "backend.test");
            register(catalog, "later", later.getLocalPort());
            var resolver = new DeferredBackendResolver("backend.test");
            var decision = new CompletableFuture<Optional<PlacementDecision>>();
            var listener = listener(catalog, resolver, Duration.ofSeconds(4));
            listener.setPlacement(player -> {
                CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS)
                        .execute(() -> decision.complete(Optional.of(
                                PlacementDecision.select(List.of("dns", "later")))));
                return decision;
            });
            try {
                int proxyPort = start(listener);
                try (Socket client = client(proxyPort)) {
                    sendLogin(client);
                    var pending = resolver.pending().get(5, TimeUnit.SECONDS);
                    // Two seconds were spent selecting. A fresh four-second DNS budget would
                    // violate this externally observed bound; the original budget has ~2s left.
                    client.setSoTimeout(3000);
                    assertLoginDisconnect(client, "Login timed out.");
                    // Some budget elapsed in the asynchronous selector before DNS began. The
                    // unresolved dial must still expire at the original routing deadline.
                    pending.promise().tryFailure(new UnknownHostException("DNS completed after route deadline"));
                    assertNoConnection(later);
                }
            } finally {
                listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void backendRegisteredDuringAsyncPluginSelectionIsResolvedWhenDecisionCompletes() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        try (var lobby = serverSocket()) {
            var selectionRequested = new CompletableFuture<Void>();
            var decision = new CompletableFuture<PlacementDecision>();
            var listener = listener(catalog);
            try (var plugins = new dev.moonbridge.core.plugin.PluginHost(catalog, listener, Duration.ofSeconds(5))) {
                plugins.load(List.of(new dev.moonbridge.api.Plugin() {
                    @Override public void onLoad(dev.moonbridge.api.PluginContext context) { }
                    @Override public Optional<dev.moonbridge.api.InitialPlacementHandler> initialPlacementHandler() {
                        return Optional.of((player, servers) -> {
                            assertEquals(List.of(), servers, "target must not exist in the invocation snapshot");
                            selectionRequested.complete(null);
                            return decision;
                        });
                    }
                }));
                plugins.enable();
                listener.setPlacement(plugins::placeInitial);
                int proxyPort = start(listener);
                try (Socket client = client(proxyPort)) {
                    sendLogin(client);
                    selectionRequested.get(5, TimeUnit.SECONDS);
                    register(catalog, "lobby", lobby.getLocalPort());
                    decision.complete(PlacementDecision.select("lobby"));
                    try (Socket backend = lobby.accept()) {
                        backend.setSoTimeout(5000);
                        var input = new DataInputStream(backend.getInputStream());
                        assertEquals(0, readVarInt(new java.io.ByteArrayInputStream(readFrame(input))));
                        assertEquals(0, readVarInt(new java.io.ByteArrayInputStream(readFrame(input))));
                        writeFrame(new DataOutputStream(backend.getOutputStream()), loginSuccess());
                        assertEquals(2, readVarInt(new java.io.ByteArrayInputStream(
                                readFrame(new DataInputStream(client.getInputStream())))));
                    }
                }
            } finally {
                listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    private static void assertPlacementDoesNotUseDefault(Optional<PlacementDecision> decision,
                                                          Throwable failure, String expectedReason) throws Exception {
        var catalog = new InMemoryBackendCatalog();
        try (var configuredDefault = serverSocket()) {
            register(catalog, "default", configuredDefault.getLocalPort());
            var listener = listener(catalog);
            listener.setInitialServers(List.of("default"));
            listener.setPlacement(player -> failure == null
                    ? CompletableFuture.completedFuture(decision)
                    : CompletableFuture.failedFuture(failure));
            try {
                int proxyPort = start(listener);
                try (Socket client = client(proxyPort)) {
                    sendLogin(client);
                    assertLoginDisconnect(client, expectedReason);
                    assertNoConnection(configuredDefault);
                }
            } finally {
                listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    private static ProxySessionListener listener(InMemoryBackendCatalog catalog) {
        return new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), catalog);
    }

    private static ProxySessionListener listener(InMemoryBackendCatalog catalog,
                                                 DeferredBackendResolver resolver, Duration timeout) {
        return new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), catalog,
                null, Duration.ofSeconds(5), timeout, Duration.ofSeconds(5), resolver);
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
        register(catalog, id, port, "127.0.0.1");
    }

    private static void register(InMemoryBackendCatalog catalog, String id, int port, String hostname) {
        catalog.register(new BackendRegistration(new BackendId(id), new BackendOwner("static", 0),
                URI.create("tcp://" + hostname + ":" + port), Map.of(), Map.of()));
    }

    private static void awaitSessionsReleased(ProxySessionListener listener) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!listener.allSessions().isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
        org.junit.jupiter.api.Assertions.assertEquals(0, listener.allSessions().size(),
                "disconnect must release the pending initial route session");
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

    private static byte[] loginSuccess() throws IOException {
        var success = new ByteArrayOutputStream();
        writeVarInt(success, 2);
        writeString(success, PLAYER_ID.toString());
        writeString(success, USERNAME);
        return success.toByteArray();
    }

    private static void assertLoginDisconnect(Socket client, String reason) throws Exception {
        var input = new java.io.ByteArrayInputStream(readFrame(new DataInputStream(client.getInputStream())));
        assertEquals(0, readVarInt(input));
        String json = readString(input, 32767);
        assertEquals(reason, readJsonText(json));
        assertEquals(0, input.available());
        assertEquals(-1, client.getInputStream().read());
    }

    private static String readJsonText(String json) throws IOException {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).path("text").asText();
    }

    private static void assertNoConnection(ServerSocket server) throws Exception {
        int oldTimeout = server.getSoTimeout();
        server.setSoTimeout(300);
        try {
            assertThrows(SocketTimeoutException.class, server::accept,
                    "backend must not receive a connection when it is outside the selected route");
        } finally {
            server.setSoTimeout(oldTimeout);
        }
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

    private static int readVarInt(java.io.ByteArrayInputStream input) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int next = input.read();
            if (next < 0) throw new IOException("unexpected end of VarInt");
            value |= (next & 0x7f) << shift;
            if ((next & 0x80) == 0) return value;
        }
        throw new IOException("invalid VarInt");
    }

    private static int readVarInt(byte[] input) throws IOException {
        return readVarInt(new java.io.ByteArrayInputStream(input));
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

    private static String readString(java.io.ByteArrayInputStream input, int maxLength) throws IOException {
        int length = readVarInt(input);
        if (length < 0 || length > maxLength || length > input.available()) {
            throw new IOException("invalid string length " + length);
        }
        return new String(input.readNBytes(length), StandardCharsets.UTF_8);
    }
}

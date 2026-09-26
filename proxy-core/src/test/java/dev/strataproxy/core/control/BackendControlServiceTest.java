package dev.strataproxy.core.control;

import dev.strataproxy.api.*;
import dev.strataproxy.app.ProxyConfiguration;
import dev.strataproxy.backendchannel.BackendChannelClient;
import dev.strataproxy.backendchannel.FrameCodec;
import dev.strataproxy.backendchannel.Wire;
import dev.strataproxy.core.backend.BackendId;
import dev.strataproxy.core.backend.InMemoryBackendCatalog;
import dev.strataproxy.core.plugin.PluginHost;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.*;

class BackendControlServiceTest {
    private static final String SECRET = "test-secret-at-least-thirty-two-bytes-long";

    @Test void twoInstancesOnOneHostCommunicateWithNoPlayers() throws Exception {
        int port = freePort();
        var catalog = new InMemoryBackendCatalog();
        var received = new AtomicReference<BackendMessage>();
        var pluginContext = new AtomicReference<PluginContext>();
        Plugin plugin = new Plugin() {
            @Override public void onLoad(PluginContext context) {
                pluginContext.set(context);
                context.backendChannels().subscribe("islands:prepare", message -> {
                    received.set(message);
                    return CompletableFuture.completedFuture("prepared".getBytes(StandardCharsets.UTF_8));
                });
            }
        };
        var config = new ProxyConfiguration.BackendChannel("127.0.0.1:" + port, Map.of(
                "island-a", client("island-a"), "island-b", client("island-b")), 5, 8);
        try (var host = new PluginHost(catalog, noPlayers(), Duration.ofSeconds(1));
             var control = new BackendControlService(config, catalog, host::dispatchBackendMessage)) {
            host.setBackendChannelTransport(control);
            host.load(List.of(plugin));
            host.enable();
            control.start();
            try (var a = backend(port, "island-a", 25565);
                 var b = backend(port, "island-b", 25566)) {
                a.registerHandler("islands:echo", payload -> CompletableFuture.completedFuture(payload));
                b.registerHandler("islands:echo", payload -> CompletableFuture.completedFuture(payload));
                assertTrue(a.awaitRegistered(5, TimeUnit.SECONDS));
                assertTrue(b.awaitRegistered(5, TimeUnit.SECONDS));
                assertEquals(2, catalog.snapshot().size());
                assertEquals("tcp://127.0.0.1:25565", catalog.find(new BackendId("island-a")).orElseThrow().address().toString());
                assertEquals("tcp://127.0.0.1:25566", catalog.find(new BackendId("island-b")).orElseThrow().address().toString());
                assertEquals("prepared", new String(a.request("islands:prepare", "hello".getBytes(StandardCharsets.UTF_8))
                        .get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8));
                assertEquals("island-a", received.get().instanceId());
                assertTrue(received.get().epoch() > 0);
                assertEquals("hello", new String(received.get().payload(), StandardCharsets.UTF_8));
                var missing = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> a.request("islands:missing", new byte[0]).get(5, TimeUnit.SECONDS));
                assertEquals(Wire.STATUS_NO_HANDLER,
                        ((BackendChannelClient.ChannelResponseException) missing.getCause()).getStatus());
                assertEquals("world", new String(pluginContext.get().backendChannels().request("island-b", "islands:echo",
                        "world".getBytes(StandardCharsets.UTF_8)).toCompletableFuture().get(5, TimeUnit.SECONDS),
                        StandardCharsets.UTF_8));
                assertEquals(BackendSendResult.SENT, pluginContext.get().backendChannels().send("island-a",
                        "islands:echo", new byte[]{1}).toCompletableFuture().get(5, TimeUnit.SECONDS));
                a.close();
                await(() -> catalog.find(new BackendId("island-a")).isEmpty());
                assertTrue(catalog.find(new BackendId("island-b")).isPresent());
            }
            await(() -> catalog.snapshot().isEmpty());
        }
    }

    @Test void badCredentialNeverRegistersBackend() throws Exception {
        int port = freePort();
        var catalog = new InMemoryBackendCatalog();
        var config = new ProxyConfiguration.BackendChannel("127.0.0.1:" + port,
                Map.of("island-a", client("island-a")), 5, 8);
        try (var control = new BackendControlService(config, catalog,
                ignored -> CompletableFuture.completedFuture(new byte[0]))) {
            control.start();
            try (var invalid = new BackendChannelClient("127.0.0.1", port, "island-a", "island-a",
                    "tcp://127.0.0.1:25565", UUID.randomUUID().toString(), "primary",
                    "wrong-secret-at-least-thirty-two-bytes".getBytes(StandardCharsets.UTF_8),
                    1000, 1000, 100, 200)) {
                assertFalse(invalid.awaitRegistered(700, TimeUnit.MILLISECONDS));
                assertTrue(catalog.snapshot().isEmpty());
            }
        }
    }

    @Test void replacementFencesOldConnectionAndAbruptLossExpiresLease() throws Exception {
        int port = freePort();
        var catalog = new InMemoryBackendCatalog();
        var config = new ProxyConfiguration.BackendChannel("127.0.0.1:" + port,
                Map.of("island-a", client("island-a")), 5, 8);
        try (var control = new BackendControlService(config, catalog,
                ignored -> CompletableFuture.completedFuture(new byte[0]))) {
            control.start();
            try (var old = rawRegistered(port); var current = rawRegistered(port)) {
                assertTrue(catalog.find(new BackendId("island-a")).isPresent());
                assertEquals(BackendSendResult.SENT, control.send("island-a", "islands:ping", new byte[0])
                        .toCompletableFuture().get(5, TimeUnit.SECONDS));
                assertEquals(Wire.MESSAGE, FrameCodec.read(
                        new java.io.DataInputStream(current.getInputStream()))[0]);
            }
            assertTrue(catalog.find(new BackendId("island-a")).isPresent(),
                    "an abrupt socket close retains the lease briefly");
            await(7, () -> catalog.find(new BackendId("island-a")).isEmpty());
        }
    }

    private static ProxyConfiguration.Client client(String name) {
        return new ProxyConfiguration.Client(name, "primary", SECRET, Set.of("127.0.0.1"), Set.of("islands"));
    }
    private static BackendChannelClient backend(int port, String name, int gamePort) {
        return new BackendChannelClient("127.0.0.1", port, name, name,
                "tcp://127.0.0.1:" + gamePort, UUID.randomUUID().toString(), "primary",
                SECRET.getBytes(StandardCharsets.UTF_8), 1000, 5000, 100, 500);
    }
    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }
    private static Socket rawRegistered(int port) throws Exception {
        var socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(2000);
        var input = new java.io.DataInputStream(socket.getInputStream());
        var output = new java.io.DataOutputStream(socket.getOutputStream());
        var hello = Wire.decodeHello(FrameCodec.read(input));
        String generation = UUID.randomUUID().toString();
        byte[] canonical = Wire.canonicalRegistration("island-a", "island-a", "tcp://127.0.0.1:25565",
                generation, "primary");
        byte[] signed = new byte[hello.nonce.length + canonical.length];
        System.arraycopy(hello.nonce, 0, signed, 0, hello.nonce.length);
        System.arraycopy(canonical, 0, signed, hello.nonce.length, canonical.length);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        FrameCodec.write(output, Wire.register("island-a", "island-a", "tcp://127.0.0.1:25565",
                generation, "primary", mac.doFinal(signed)));
        assertTrue(Wire.decodeRegistered(FrameCodec.read(input)) > 0);
        return socket;
    }
    private static void await(BooleanSupplier condition) throws Exception {
        await(3, condition);
    }
    private static void await(int seconds, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) fail("condition not met");
            Thread.sleep(10);
        }
    }
    private static Players noPlayers() {
        return new Players() {
            @Override public Optional<PlayerView> find(PlayerIdentity identity) { return Optional.empty(); }
            @Override public List<PlayerView> online() { return List.of(); }
            @Override public CompletionStage<TransferResult> transfer(PlayerIdentity identity, String backendName) {
                return CompletableFuture.completedFuture(TransferResult.failed("unavailable"));
            }
            @Override public CompletionStage<MessageResult> sendMessage(PlayerIdentity identity, String message) {
                return CompletableFuture.completedFuture(MessageResult.NOT_CONNECTED);
            }
            @Override public CompletionStage<DisconnectResult> disconnect(PlayerIdentity identity, String reason) {
                return CompletableFuture.completedFuture(DisconnectResult.NOT_CONNECTED);
            }
        };
    }
}

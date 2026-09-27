package dev.moonbridge.core.control;

import dev.moonbridge.app.ProxyConfiguration;
import dev.moonbridge.backendchannel.BackendChannelClient;
import dev.moonbridge.backendchannel.FrameCodec;
import dev.moonbridge.backendchannel.Wire;
import dev.moonbridge.core.backend.BackendId;
import dev.moonbridge.core.backend.InMemoryBackendCatalog;
import dev.moonbridge.messaging.Endpoint;
import dev.moonbridge.messaging.Message;
import dev.moonbridge.messaging.SendResult;
import dev.moonbridge.messaging.internal.LocalMessaging;
import dev.moonbridge.messaging.protocol.MessageCodec;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.Socket;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendControlServiceTest {
    private static final String SECRET = "test-secret-at-least-thirty-two-bytes-long";

    @Test void badCredentialNeverRegistersBackend() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        var catalog = new InMemoryBackendCatalog();
        var config = new ProxyConfiguration.BackendChannel("127.0.0.1:" + port,
                Map.of("island-a", client("island-a")), 5, 8);
        var timer = Executors.newSingleThreadScheduledExecutor();
        var local = new LocalMessaging(Endpoint.proxy(), new LocalMessaging.Outbound() {
            @Override public java.util.concurrent.CompletionStage<dev.moonbridge.messaging.SendResult> send(
                    dev.moonbridge.messaging.Message message) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException());
            }
            @Override public java.util.concurrent.CompletionStage<dev.moonbridge.messaging.Message> request(
                    dev.moonbridge.messaging.Message message, Duration timeout) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException());
            }
            @Override public java.util.concurrent.CompletionStage<dev.moonbridge.messaging.PublishResult> publish(
                    dev.moonbridge.messaging.Message event) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException());
            }
        }, Runnable::run, timer);
        try (var control = new BackendControlService(config, catalog, local)) {
            control.start();
            try (var invalid = new BackendChannelClient("127.0.0.1", port, "island-a", "island-a",
                    "tcp://127.0.0.1:25565", UUID.randomUUID().toString(), "primary",
                    "wrong-secret-at-least-thirty-two-bytes".getBytes(StandardCharsets.UTF_8),
                    1000, 100, 200)) {
                assertFalse(invalid.awaitRegistered(700, TimeUnit.MILLISECONDS));
                assertTrue(catalog.snapshot().isEmpty());
            }
        } finally {
            local.close();
            timer.shutdownNow();
        }
    }

    @Test void goodbyeRemovesRegisteredBackendFromCatalog() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        var catalog = new InMemoryBackendCatalog();
        var config = new ProxyConfiguration.BackendChannel("127.0.0.1:" + port,
                Map.of("island-a", client("island-a")), 5, 8);
        var timer = Executors.newSingleThreadScheduledExecutor();
        var local = localMessaging(timer);
        try (var control = new BackendControlService(config, catalog, local)) {
            control.start();
            try (var backend = backend(port, "island-a", 25565)) {
                assertTrue(backend.awaitRegistered(5, TimeUnit.SECONDS));
                assertEquals(1, catalog.snapshot().size());
                assertEquals("tcp://127.0.0.1:25565",
                        catalog.find(new BackendId("island-a")).orElseThrow().address().toString());
                backend.close();
                await(() -> catalog.snapshot().isEmpty());
            }
        } finally {
            local.close();
            timer.shutdownNow();
        }
    }

    @Test void replacementFencesOldConnectionAndAbruptLossExpiresLease() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        var catalog = new InMemoryBackendCatalog();
        var config = new ProxyConfiguration.BackendChannel("127.0.0.1:" + port,
                Map.of("island-a", client("island-a")), 5, 8);
        var timer = Executors.newSingleThreadScheduledExecutor();
        var local = localMessaging(timer);
        try (var control = new BackendControlService(config, catalog, local)) {
            control.start();
            try (var old = rawRegistered(port); var current = rawRegistered(port)) {
                assertTrue(catalog.find(new BackendId("island-a")).isPresent());
                var outbound = control.send(Message.event("islands:ping", Endpoint.proxy(),
                        Endpoint.backend("island-a"), new byte[0]));
                byte[] frame = FrameCodec.read(new DataInputStream(current.getInputStream()));
                MessageCodec.IncomingMessage sent = MessageCodec.decodeMessage(frame);
                assertEquals(Endpoint.backend("island-a"), sent.message.target());
                FrameCodec.write(new DataOutputStream(current.getOutputStream()),
                        MessageCodec.sendResult(sent.operationId, SendResult.ACCEPTED));
                assertEquals(SendResult.ACCEPTED, outbound.toCompletableFuture().get(5, TimeUnit.SECONDS));
                assertThrows(java.io.IOException.class,
                        () -> FrameCodec.read(new DataInputStream(old.getInputStream())));
            }
            assertTrue(catalog.find(new BackendId("island-a")).isPresent(),
                    "an abrupt socket close retains the lease briefly");
            await(7, () -> catalog.find(new BackendId("island-a")).isEmpty());
        } finally {
            local.close();
            timer.shutdownNow();
        }
    }

    private static ProxyConfiguration.Client client(String name) {
        return new ProxyConfiguration.Client(name, "primary", SECRET, Set.of("127.0.0.1"), Set.of("islands"));
    }

    private static BackendChannelClient backend(int port, String name, int gamePort) {
        return new BackendChannelClient("127.0.0.1", port, name, name,
                "tcp://127.0.0.1:" + gamePort, UUID.randomUUID().toString(), "primary",
                SECRET.getBytes(StandardCharsets.UTF_8), 1000, 100, 200);
    }

    private static Socket rawRegistered(int port) throws Exception {
        var socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(2000);
        var input = new DataInputStream(socket.getInputStream());
        var output = new DataOutputStream(socket.getOutputStream());
        var hello = Wire.decodeHello(FrameCodec.read(input));
        String generation = UUID.randomUUID().toString();
        String address = "tcp://127.0.0.1:25565";
        byte[] canonical = Wire.canonicalRegistration("island-a", "island-a", address, generation, "primary");
        byte[] signed = new byte[hello.nonce.length + canonical.length];
        System.arraycopy(hello.nonce, 0, signed, 0, hello.nonce.length);
        System.arraycopy(canonical, 0, signed, hello.nonce.length, canonical.length);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        FrameCodec.write(output, Wire.register("island-a", "island-a", address,
                generation, "primary", mac.doFinal(signed)));
        assertTrue(Wire.decodeRegistered(FrameCodec.read(input)) > 0);
        return socket;
    }

    private static LocalMessaging localMessaging(java.util.concurrent.ScheduledExecutorService timer) {
        return new LocalMessaging(Endpoint.proxy(), new LocalMessaging.Outbound() {
            @Override public java.util.concurrent.CompletionStage<SendResult> send(Message message) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException());
            }
            @Override public java.util.concurrent.CompletionStage<Message> request(Message message, Duration timeout) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException());
            }
            @Override public java.util.concurrent.CompletionStage<dev.moonbridge.messaging.PublishResult> publish(
                    Message event) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException());
            }
        }, Runnable::run, timer);
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception { await(3, condition); }
    private static void await(int seconds, java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) fail("condition not met");
            Thread.sleep(10);
        }
    }
}

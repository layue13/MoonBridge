package dev.strataproxy.agent;

import dev.strataproxy.app.ProxyConfig;
import dev.strataproxy.backend.api.DeliveryMode;
import dev.strataproxy.backend.internal.ChannelFrame;
import dev.strataproxy.backend.internal.ChannelWire;
import dev.strataproxy.plugin.service.ServerMutationResult;
import dev.strataproxy.plugin.service.ServerRegistration;
import dev.strataproxy.plugin.service.ServerRemoval;
import dev.strataproxy.plugin.service.ServerService;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendAgentServerTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void invalidSignaturesDoNotConsumeBoundedNonceCapacity() throws Exception {
        var config = new ProxyConfig.BackendAgentConfig(true, new InetSocketAddress("127.0.0.1", 0), SECRET,
                java.time.Duration.ofSeconds(30), 1, 1, 1);
        try (var server = new BackendAgentServer(config, new AcceptingServers())) {
            server.start();
            var port = ((InetSocketAddress) server.localAddress()).getPort();
            assertTrue(call(port, envelope("bad-nonce", "wrong-secret", "instance-a")).contains("invalid request signature"));
            assertTrue(call(port, envelope("valid-nonce", SECRET, "instance-a")).contains("\"success\":true"));
            assertTrue(call(port, envelope("second-nonce", SECRET, "instance-a")).contains("nonce cache is at capacity"));
        }
    }

    @Test
    void oversizedRequestIsRejectedBeforeJsonParsing() throws Exception {
        var config = new ProxyConfig.BackendAgentConfig(true, new InetSocketAddress("127.0.0.1", 0), SECRET,
                java.time.Duration.ofSeconds(30), 1, 0, 4);
        try (var server = new BackendAgentServer(config, new AcceptingServers())) {
            server.start();
            var port = ((InetSocketAddress) server.localAddress()).getPort();
            try (var socket = new Socket("127.0.0.1", port);
                 var out = new BufferedWriter(new java.io.OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                 var in = new BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                out.write("x".repeat(65 * 1024)); out.newLine(); out.flush();
                assertTrue(in.readLine().contains("request is missing or too large"));
            }
        }
    }

    @Test
    void expiredInstanceCannotUnregisterAReplacementThatIsStillRegistering() throws Exception {
        var config = new ProxyConfig.BackendAgentConfig(true, new InetSocketAddress("127.0.0.1", 0), SECRET,
                java.time.Duration.ofMillis(1), 4, 4, 32);
        var servers = new BlockingUnregisterServers();
        try (var server = new BackendAgentServer(config, servers)) {
            server.start();
            var port = ((InetSocketAddress) server.localAddress()).getPort();
            assertTrue(call(port, envelope("old-registration", SECRET, "instance-old")).contains("\"success\":true"));
            Thread.sleep(10);

            var sweeper = new Thread(server::expireLeases);
            sweeper.start();
            assertTrue(servers.unregisterStarted.await(1, TimeUnit.SECONDS));

            var requests = Executors.newSingleThreadExecutor();
            try {
                var replacement = requests.submit(() -> call(port, envelope("new-registration", SECRET, "instance-new")));
                Thread.sleep(50);
                assertFalse(replacement.isDone());

                servers.allowUnregister.complete(ServerMutationResult.success("unregistered", null));
                sweeper.join(1_000);
                assertTrue(replacement.get(1, TimeUnit.SECONDS).contains("\"success\":true"));
            } finally {
                requests.shutdownNow();
            }
        }
        assertEquals(java.util.List.of("register", "unregister", "register"), servers.operations);
    }

    @Test
    void streamDoesNotStarveRegistrationWorkersAndCarriesMessagesWithoutPlayers() throws Exception {
        var config = new ProxyConfig.BackendAgentConfig(true, new InetSocketAddress("127.0.0.1", 0), SECRET,
                java.time.Duration.ofSeconds(30), 1, 1, 32);
        try (var broker = new BackendMessageBroker(); var server = new BackendAgentServer(config, new AcceptingServers(), broker)) {
            server.start();
            var port = ((InetSocketAddress) server.localAddress()).getPort();
            assertTrue(call(port, envelope("registration", SECRET, "instance-a")).contains("\"success\":true"));
            try (var stream = new Socket("127.0.0.1", port)) {
                stream.setSoTimeout(2_000);
                stream.getOutputStream().write((envelope("stream", "stream", SECRET, "instance-a") + "\n").getBytes(StandardCharsets.UTF_8));
                assertTrue(readLine(stream).contains("\"outcome\":\"stream_connected\""));
                assertTrue(call(port, envelope("heartbeat", "heartbeat", SECRET, "instance-a")).contains("\"success\":true"));
                ChannelWire.write(stream.getOutputStream(), new ChannelFrame(ChannelFrame.SUBSCRIBE,
                        "", "", "", "", "test:events", "", "", new byte[0]));
                dev.strataproxy.plugin.service.ChannelPublishResult sent = null;
                for (int attempt = 0; attempt < 100; attempt++) {
                    sent = broker.forPlugin("test").publishTo("survival-1", "test:events", new byte[] {7}, "corr", DeliveryMode.RELIABLE)
                            .toCompletableFuture().join();
                    if (sent.accepted()) break;
                    Thread.sleep(10);
                }
                assertTrue(sent.accepted());
                var frame = ChannelWire.read(stream.getInputStream());
                assertEquals(ChannelFrame.MESSAGE, frame.type);
                assertEquals(sent.messageId(), frame.messageId);
                assertEquals("corr", frame.correlationId);
                assertEquals(7, frame.payload[0]);
                ChannelWire.write(stream.getOutputStream(), new ChannelFrame(ChannelFrame.ACK,
                        "", frame.messageId, "", "", "", "", "", new byte[0]));
            }
        }
    }

    private static String readLine(Socket socket) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        for (int value; (value = socket.getInputStream().read()) != '\n';) {
            if (value < 0 || bytes.size() >= 4096) throw new java.io.IOException("missing stream greeting");
            bytes.write(value);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static String call(int port, String value) throws Exception {
        try (var socket = new Socket("127.0.0.1", port);
             var out = new BufferedWriter(new java.io.OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
             var in = new BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            out.write(value); out.newLine(); out.flush();
            return in.readLine();
        }
    }

    private static String envelope(String nonce, String secret, String instanceId) throws Exception {
        return envelope("register", nonce, secret, instanceId);
    }

    private static String envelope(String operation, String nonce, String secret, String instanceId) throws Exception {
        var payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"operation\":\"" + operation + "\",\"name\":\"survival-1\",\"instanceId\":\"" + instanceId + "\",\"host\":\"127.0.0.1\",\"port\":25565}").getBytes(StandardCharsets.UTF_8));
        var timestamp = System.currentTimeMillis();
        var signed = "survival-1\n" + timestamp + "\n" + nonce + "\n" + payload;
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        var signature = java.util.HexFormat.of().formatHex(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
        return "{\"agentId\":\"survival-1\",\"timestamp\":" + timestamp + ",\"nonce\":\"" + nonce + "\",\"payload\":\"" + payload + "\",\"signature\":\"" + signature + "\"}";
    }

    private static class AcceptingServers implements ServerService {
        @Override public CompletionStage<ServerMutationResult> register(ServerRegistration registration) {
            return CompletableFuture.completedFuture(ServerMutationResult.success("registered", null));
        }
        @Override public CompletionStage<ServerMutationResult> unregister(String name, ServerRemoval removal) {
            return CompletableFuture.completedFuture(ServerMutationResult.success("unregistered", null));
        }
        @Override public java.util.Optional<dev.strataproxy.plugin.service.ServerView> find(String name) { return java.util.Optional.empty(); }
        @Override public java.util.Optional<dev.strataproxy.plugin.service.ServerView> firstWithTag(String tag) { return java.util.Optional.empty(); }
        @Override public java.util.Collection<dev.strataproxy.plugin.service.ServerView> servers() { return java.util.List.of(); }
    }

    private static final class BlockingUnregisterServers extends AcceptingServers {
        private final CopyOnWriteArrayList<String> operations = new CopyOnWriteArrayList<>();
        private final CountDownLatch unregisterStarted = new CountDownLatch(1);
        private final CompletableFuture<ServerMutationResult> allowUnregister = new CompletableFuture<>();

        @Override public CompletionStage<ServerMutationResult> register(ServerRegistration registration) {
            operations.add("register");
            return CompletableFuture.completedFuture(ServerMutationResult.success("registered", null));
        }

        @Override public CompletionStage<ServerMutationResult> unregister(String name, ServerRemoval removal) {
            operations.add("unregister");
            unregisterStarted.countDown();
            return allowUnregister;
        }
    }
}

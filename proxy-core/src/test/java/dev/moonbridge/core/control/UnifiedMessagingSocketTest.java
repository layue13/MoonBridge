package dev.moonbridge.core.control;

import dev.moonbridge.app.ProxyConfiguration;
import dev.moonbridge.backendchannel.BackendChannelClient;
import dev.moonbridge.core.backend.InMemoryBackendCatalog;
import dev.moonbridge.messaging.*;
import dev.moonbridge.messaging.internal.LocalMessaging;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Actual TCP paths; the fixture has no Minecraft clients or player sessions. */
class UnifiedMessagingSocketTest {
    private static final String CHANNEL = "islands:control";
    private static final byte[] DATA = "payload".getBytes(StandardCharsets.UTF_8);

    @Test void backendProxyAndBackendBackendKeepEndToEndMessageIdentity() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicReference<Message> proxyRequest = new AtomicReference<>();
            AtomicReference<Message> backendRequest = new AtomicReference<>();
            f.proxy.channel(CHANNEL).onRequest(message -> {
                proxyRequest.set(message);
                return CompletableFuture.completedFuture(message.payload());
            });
            f.b.messaging("receiver").channel(CHANNEL).onRequest(message -> {
                backendRequest.set(message);
                return CompletableFuture.completedFuture(message.payload());
            });
            var a = f.a.messaging("sender").channel(CHANNEL);
            Message proxyReply = await(a.request(Endpoint.proxy(), DATA));
            assertExchange(proxyRequest.get(), proxyReply, Endpoint.backend("a"), Endpoint.proxy());
            Message backendReply = await(a.request(Endpoint.backend("b"), DATA));
            assertExchange(backendRequest.get(), backendReply, Endpoint.backend("a"), Endpoint.backend("b"));
            assertNotEquals(proxyRequest.get().id(), backendRequest.get().id());

            AtomicReference<Message> reverseRequest = new AtomicReference<>();
            a.onRequest(message -> {
                reverseRequest.set(message);
                return CompletableFuture.completedFuture(message.payload());
            });
            Message reverseReply = await(f.proxy.channel(CHANNEL).request(Endpoint.backend("a"), DATA));
            assertExchange(reverseRequest.get(), reverseReply, Endpoint.proxy(), Endpoint.backend("a"));
        }
    }

    @Test void publishSharesIdentityAndDisablingOnePluginKeepsOtherSubscribers() throws Exception {
        try (Fixture f = new Fixture()) {
            List<Message> proxyEvents = new CopyOnWriteArrayList<>();
            List<Message> aEvents = new CopyOnWriteArrayList<>();
            List<Message> bFirstEvents = new CopyOnWriteArrayList<>();
            List<Message> bSecondEvents = new CopyOnWriteArrayList<>();
            f.proxy.channel(CHANNEL).subscribe(proxyEvents::add);
            var sender = f.a.messaging("sender").channel(CHANNEL);
            sender.subscribe(aEvents::add);
            Messaging bFirst = f.b.messaging("first");
            bFirst.channel(CHANNEL).subscribe(bFirstEvents::add);
            Messaging bSecond = f.b.messaging("second");
            bSecond.channel(CHANNEL).subscribe(bSecondEvents::add);

            PublishResult first = await(sender.publish(DATA));
            assertEquals(Map.of(Endpoint.proxy(), SendResult.ACCEPTED, Endpoint.backend("a"), SendResult.ACCEPTED,
                    Endpoint.backend("b"), SendResult.ACCEPTED), first.results());
            eventually(() -> proxyEvents.size() == 1 && aEvents.size() == 1
                    && bFirstEvents.size() == 1 && bSecondEvents.size() == 1);
            for (Message event : List.of(proxyEvents.getFirst(), aEvents.getFirst(),
                    bFirstEvents.getFirst(), bSecondEvents.getFirst())) {
                assertEquals(first.messageId(), event.id());
                assertEquals(Endpoint.backend("a"), event.source());
                assertEquals(MessageKind.EVENT, event.kind());
                assertNull(event.replyTo());
            }

            bFirst.close();
            assertEquals(MessagingException.Code.CLOSED,
                    assertThrows(MessagingException.class, () -> bFirst.channel(CHANNEL)).code());
            SendReceipt direct = await(sender.send(Endpoint.backend("b"), DATA));
            assertEquals(SendResult.ACCEPTED, direct.result());
            eventually(() -> bSecondEvents.size() == 2);
            assertEquals(direct.messageId(), bSecondEvents.get(1).id());
            assertEquals(1, bFirstEvents.size());
            assertEquals(1, aEvents.size());
            assertEquals(1, proxyEvents.size());
            bSecond.close();
            assertEquals(SendResult.NO_SUBSCRIBER, await(sender.send(Endpoint.backend("b"), DATA)).result());
        }
    }

    @Test void concurrentRepliesCanArriveInReverseOrder() throws Exception {
        try (Fixture f = new Fixture()) {
            Map<Integer, CompletableFuture<byte[]>> replies = new ConcurrentHashMap<>();
            Map<Integer, Message> requests = new ConcurrentHashMap<>();
            f.b.messaging("handler").channel(CHANNEL).onRequest(message -> {
                int number = message.payload()[0] & 255;
                requests.put(number, message);
                CompletableFuture<byte[]> reply = new CompletableFuture<>();
                replies.put(number, reply);
                return reply;
            });
            var channel = f.a.messaging("caller").channel(CHANNEL);
            List<CompletionStage<Message>> calls = new ArrayList<>();
            for (int i = 0; i < 12; i++) calls.add(channel.request(Endpoint.backend("b"), new byte[]{(byte) i}));
            eventually(() -> replies.size() == 12);
            for (int i = 11; i >= 0; i--) replies.get(i).complete(new byte[]{(byte) i});
            for (int i = 0; i < 12; i++) {
                Message response = await(calls.get(i));
                assertEquals(requests.get(i).id(), response.replyTo());
                assertArrayEquals(new byte[]{(byte) i}, response.payload());
            }
        }
    }

    @Test void timeoutDoesNotReplayAndMissingHandlerHasDistinctFailure() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger executed = new AtomicInteger();
            CompletableFuture<byte[]> slowReply = new CompletableFuture<>();
            f.b.messaging("handler").channel(CHANNEL).onRequest(message -> {
                executed.incrementAndGet();
                return slowReply;
            });
            var channel = f.a.messaging("caller").channel(CHANNEL);
            var pending = channel.request(Endpoint.backend("b"), DATA, Duration.ofMillis(200));
            assertEquals(MessagingException.Code.TIMED_OUT, failure(pending).code());
            slowReply.complete(DATA);
            assertEquals(1, executed.get());
            assertEquals(MessagingException.Code.NO_HANDLER,
                    failure(f.a.messaging("missing").channel("islands:missing")
                            .request(Endpoint.backend("b"), DATA)).code());
            assertEquals(SendResult.NOT_CONNECTED,
                    await(channel.send(Endpoint.backend("offline"), DATA)).result());
        }
    }

    @Test void sendAndReceivePermissionsAreIndependentAndRejectionKeepsConnection() throws Exception {
        try (Fixture f = new Fixture(Set.of())) {
            AtomicInteger bExecutions = new AtomicInteger();
            var receiver = f.b.messaging("receiver").channel(CHANNEL);
            receiver.subscribe(message -> bExecutions.incrementAndGet());
            receiver.onRequest(message -> {
                bExecutions.incrementAndGet();
                return CompletableFuture.completedFuture(DATA);
            });
            f.proxy.channel(CHANNEL).onRequest(message -> CompletableFuture.completedFuture(DATA));
            var sender = f.a.messaging("sender").channel(CHANNEL);
            assertEquals(SendResult.REJECTED, await(sender.send(Endpoint.backend("b"), DATA)).result());
            assertEquals(MessagingException.Code.REJECTED,
                    failure(sender.request(Endpoint.backend("b"), DATA)).code());
            assertFalse(await(sender.publish(DATA)).results().containsKey(Endpoint.backend("b")));
            assertEquals(0, bExecutions.get());
            // B can still send to the proxy despite having no receive permission.
            assertArrayEquals(DATA, await(receiver.request(Endpoint.proxy(), DATA)).payload());
            var forbidden = f.a.messaging("forbidden").channel("private:control");
            assertEquals(MessagingException.Code.REJECTED,
                    failure(forbidden.request(Endpoint.proxy(), DATA)).code());
            assertEquals(SendResult.REJECTED, await(forbidden.send(Endpoint.proxy(), DATA)).result());
            assertArrayEquals(DATA, await(sender.request(Endpoint.proxy(), DATA)).payload());
        }
    }

    private static void assertExchange(Message request, Message reply, Endpoint source, Endpoint target) {
        assertNotNull(request);
        assertEquals(source, request.source());
        assertEquals(target, request.target());
        assertEquals(MessageKind.REQUEST, request.kind());
        assertEquals(MessageKind.REPLY, reply.kind());
        assertEquals(request.id(), reply.replyTo());
        assertNotEquals(request.id(), reply.id());
        assertEquals(target, reply.source());
        assertEquals(source, reply.target());
        assertArrayEquals(DATA, reply.payload());
    }

    private static <T> T await(CompletionStage<T> value) throws Exception {
        return value.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static MessagingException failure(CompletionStage<?> stage) {
        ExecutionException error = assertThrows(ExecutionException.class, () -> await(stage));
        Throwable cause = error.getCause();
        while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
        return assertInstanceOf(MessagingException.class, cause);
    }

    private static void eventually(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) fail("condition did not become true");
            Thread.sleep(5);
        }
    }

    private static final class Fixture implements AutoCloseable {
        static final String SECRET = "test-messaging-secret-at-least-thirty-two-bytes";
        final ExecutorService workers = Executors.newFixedThreadPool(4, Fixture::daemon);
        final ScheduledExecutorService timer = Executors.newScheduledThreadPool(2, Fixture::daemon);
        final LocalMessaging local;
        final Messaging proxy;
        final BackendControlService control;
        final BackendChannelClient a;
        final BackendChannelClient b;

        Fixture() throws Exception { this(Set.of("islands")); }

        Fixture(Set<String> bReceiveNamespaces) throws Exception {
            AtomicReference<BackendControlService> transport = new AtomicReference<>();
            local = new LocalMessaging(Endpoint.proxy(), new LocalMessaging.Outbound() {
                public CompletionStage<SendResult> send(Message message) { return transport.get().send(message); }
                public CompletionStage<Message> request(Message message, Duration timeout) {
                    return transport.get().request(message, timeout);
                }
                public CompletionStage<PublishResult> publish(Message message) { return transport.get().publish(message); }
            }, workers, timer);
            int port;
            try (ServerSocket reservation = new ServerSocket(0)) { port = reservation.getLocalPort(); }
            var clients = Map.of("instance-a", clientConfig("a"), "instance-b",
                    new ProxyConfiguration.Client("b", "primary", SECRET, Set.of("127.0.0.1"),
                            Set.of("islands"), bReceiveNamespaces));
            var config = new ProxyConfiguration.BackendChannel("127.0.0.1:" + port, clients, 5, 8);
            control = new BackendControlService(config, new InMemoryBackendCatalog(), local);
            transport.set(control);
            control.start();
            proxy = local.openScope("proxy");
            a = client(port, "a", 25565);
            b = client(port, "b", 25566);
            try {
                assertTrue(a.awaitRegistered(5, TimeUnit.SECONDS));
                assertTrue(b.awaitRegistered(5, TimeUnit.SECONDS));
            } catch (Throwable failure) {
                close();
                throw failure;
            }
        }

        static ProxyConfiguration.Client clientConfig(String name) {
            return new ProxyConfiguration.Client(name, "primary", SECRET, Set.of("127.0.0.1"), Set.of("islands"));
        }

        static BackendChannelClient client(int port, String name, int gamePort) {
            return new BackendChannelClient("127.0.0.1", port, "instance-" + name, name,
                    "tcp://127.0.0.1:" + gamePort, UUID.randomUUID().toString(), "primary",
                    SECRET.getBytes(StandardCharsets.UTF_8), 1_000, 50, 100);
        }

        static Thread daemon(Runnable work) {
            Thread thread = new Thread(work, "unified-messaging-test");
            thread.setDaemon(true);
            return thread;
        }

        @Override public void close() {
            a.close();
            b.close();
            control.close();
            local.close();
            workers.shutdownNow();
            timer.shutdownNow();
        }
    }
}

import dev.moonbridge.backendchannel.BackendChannelClient;
import dev.moonbridge.messaging.Endpoint;
import dev.moonbridge.messaging.Message;
import dev.moonbridge.messaging.MessageChannel;
import dev.moonbridge.messaging.PublishResult;
import dev.moonbridge.messaging.SendResult;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Run with an actual Java 8 VM against ChannelRouterFixture; uses no players. */
public final class ChannelJava8Probe {
    public static void main(String[] args) throws Exception {
        if (!System.getProperty("java.specification.version").equals("1.8")) {
            throw new IllegalStateException("This probe must run on Java 8");
        }
        int port = Integer.parseInt(args[0]);
        byte[] bytes = "java8-payload".getBytes(StandardCharsets.UTF_8);
        try (BackendChannelClient a = client(port, "a", 25565);
             BackendChannelClient b = client(port, "b", 25566)) {
            require(a.awaitRegistered(8, TimeUnit.SECONDS), "backend a did not register");
            require(b.awaitRegistered(8, TimeUnit.SECONDS), "backend b did not register");
            MessageChannel sender = a.messaging("probe-a").channel("probe:channel");
            MessageChannel receiver = b.messaging("probe-b").channel("probe:channel");
            AtomicReference<Message> received = new AtomicReference<Message>();
            receiver.onRequest(message -> {
                received.set(message);
                return CompletableFuture.completedFuture(message.payload());
            });
            Message proxyReply = sender.request(Endpoint.proxy(), bytes).toCompletableFuture().get(5, TimeUnit.SECONDS);
            require(Endpoint.proxy().equals(proxyReply.source()), "wrong proxy reply source");
            Message reply = sender.request(Endpoint.backend("b"), bytes).toCompletableFuture().get(5, TimeUnit.SECONDS);
            require(Endpoint.backend("a").equals(received.get().source()), "wrong forwarded source");
            require(Endpoint.backend("b").equals(reply.source()), "wrong reply source");
            require(received.get().id().equals(reply.replyTo()), "reply correlation changed in transit");
            require(!reply.id().equals(reply.replyTo()), "reply lacks an independent ID");

            CountDownLatch notifications = new CountDownLatch(3);
            AtomicReference<UUID> publishedId = new AtomicReference<UUID>();
            sender.subscribe(message -> notifications.countDown());
            receiver.subscribe(message -> { publishedId.set(message.id()); notifications.countDown(); });
            b.messaging("probe-second-plugin").channel("probe:channel")
                    .subscribe(message -> notifications.countDown());
            PublishResult publication = sender.publish(bytes).toCompletableFuture().get(5, TimeUnit.SECONDS);
            require(publication.results().size() == 3, "publication missed a node");
            require(publication.results().get(Endpoint.backend("a")) == SendResult.ACCEPTED, "sender subscriber missing");
            require(publication.results().get(Endpoint.backend("b")) == SendResult.ACCEPTED, "backend subscribers missing");
            require(notifications.await(5, TimeUnit.SECONDS), "publication missed a plugin");
            require(publication.messageId().equals(publishedId.get()), "publication ID changed in transit");
            System.out.println("JAVA8_CHANNEL_PASS java=" + System.getProperty("java.version")
                    + " requests=2 backendSubscribers=3 endToEndIdentity=true");
        }
    }

    private static BackendChannelClient client(int port, String name, int gamePort) {
        return new BackendChannelClient("127.0.0.1", port, "probe-" + name, name,
                "tcp://127.0.0.1:" + gamePort, UUID.randomUUID().toString(), "primary",
                "isolated-channel-probe-secret-at-least-32-bytes".getBytes(StandardCharsets.UTF_8));
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}

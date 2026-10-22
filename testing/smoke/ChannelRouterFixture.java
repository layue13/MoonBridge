import dev.moonbridge.app.ProxyConfiguration;
import dev.moonbridge.core.backend.InMemoryBackendCatalog;
import dev.moonbridge.core.control.BackendControlService;
import dev.moonbridge.messaging.*;
import dev.moonbridge.messaging.internal.LocalMessaging;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Isolated JDK 25 router for the separately launched Java 8 probe. */
public final class ChannelRouterFixture {
    public static void main(String[] args) throws Exception {
        int port;
        try (ServerSocket reservation = new ServerSocket(0)) { port = reservation.getLocalPort(); }
        ExecutorService workers = Executors.newFixedThreadPool(4);
        ScheduledExecutorService timer = Executors.newScheduledThreadPool(2);
        AtomicReference<BackendControlService> transport = new AtomicReference<>();
        try (LocalMessaging local = new LocalMessaging(Endpoint.proxy(), new LocalMessaging.Outbound() {
            public CompletionStage<SendResult> send(Message message) { return transport.get().send(message); }
            public CompletionStage<Message> request(Message message, Duration timeout) { return transport.get().request(message, timeout); }
            public CompletionStage<PublishResult> publish(Message message) { return transport.get().publish(message); }
        }, workers, timer)) {
            var config = new ProxyConfiguration.BackendChannel("127.0.0.1:" + port,
                    Map.of("probe-a", client("a"), "probe-b", client("b")), 30, 8);
            try (var control = new BackendControlService(config, new InMemoryBackendCatalog(), local)) {
                transport.set(control);
                var channel = local.openScope("fixture").channel("probe:channel");
                channel.onRequest(message -> CompletableFuture.completedFuture(message.payload()));
                channel.subscribe(message -> { });
                control.start();
                System.out.println("CHANNEL_FIXTURE_READY " + port);
                System.out.flush();
                System.in.read();
            }
        } finally {
            workers.shutdownNow();
            timer.shutdownNow();
        }
    }

    private static ProxyConfiguration.Client client(String name) {
        return new ProxyConfiguration.Client(name, "primary",
                "isolated-channel-probe-secret-at-least-32-bytes", Set.of("127.0.0.1"), Set.of("probe"));
    }
}

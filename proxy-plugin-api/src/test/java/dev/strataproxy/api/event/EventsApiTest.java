package dev.strataproxy.api.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.strataproxy.api.AccessDecision;
import dev.strataproxy.api.PlayerIdentity;
import dev.strataproxy.api.PlayerView;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class EventsApiTest {
    private static final InetSocketAddress ADDRESS = new InetSocketAddress("127.0.0.1", 25565);
    private static final PlayerView PLAYER = new PlayerView(
            new PlayerIdentity(UUID.randomUUID(), 1), "TestPlayer", Optional.of("lobby"));

    @Test
    void eventRecordsRequireTheirNonNullValues() {
        assertThrows(NullPointerException.class, () -> new ConnectionEvent(null));
        assertThrows(NullPointerException.class, () -> new LoginEvent(null, ADDRESS, true));
        assertThrows(NullPointerException.class, () -> new LoginEvent(PLAYER, null, true));
        assertThrows(NullPointerException.class, () -> new ServerConnectedEvent(null, Optional.empty()));
        assertThrows(NullPointerException.class, () -> new ServerConnectedEvent(PLAYER, null));
        assertThrows(IllegalArgumentException.class, () -> new ServerConnectedEvent(
                new PlayerView(PLAYER.identity(), PLAYER.username(), Optional.empty()), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ServerConnectedEvent(
                PLAYER, Optional.of(" ")));
        assertThrows(NullPointerException.class, () -> new PlayerDisconnectedEvent(null));
    }

    @Test
    void typedSubscriptionOverloadsCompileAndRemainDistinct() {
        Events events = new Events() {
            @Override
            public <E extends NotificationEvent> EventSubscription subscribe(
                    Class<E> eventType, Consumer<? super E> listener) {
                return () -> { };
            }

            @Override
            public <E extends AccessEvent> EventSubscription subscribe(
                    Class<E> eventType, AccessListener<? super E> listener) {
                return () -> { };
            }
        };

        EventSubscription access = events.subscribe(ConnectionEvent.class,
                event -> CompletableFuture.completedFuture(AccessDecision.allow()));
        EventSubscription notification = events.subscribe(ServerConnectedEvent.class,
                event -> assertEquals("TestPlayer", event.player().username()));

        access.close();
        notification.close();
    }

}

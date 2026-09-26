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
import org.junit.jupiter.api.Test;

class EventsApiTest {
    private static final InetSocketAddress ADDRESS = new InetSocketAddress("127.0.0.1", 25565);
    private static final PlayerView PLAYER = new PlayerView(
            new PlayerIdentity(UUID.randomUUID(), 1), "TestPlayer", Optional.of("lobby"));

    @Test
    void eventRecordsRequireTheirNonNullValues() {
        assertThrows(NullPointerException.class, () -> new ConnectionAdmissionEvent(null));
        assertThrows(NullPointerException.class, () -> new PlayerAdmissionEvent(null, ADDRESS, true));
        assertThrows(NullPointerException.class, () -> new PlayerAdmissionEvent(PLAYER, null, true));
        assertThrows(NullPointerException.class, () -> new ServerConnectedEvent(null, Optional.empty()));
        assertThrows(NullPointerException.class, () -> new ServerConnectedEvent(PLAYER, null));
        assertThrows(IllegalArgumentException.class, () -> new ServerConnectedEvent(
                new PlayerView(PLAYER.identity(), PLAYER.username(), Optional.empty()), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ServerConnectedEvent(
                PLAYER, Optional.of(" ")));
        assertThrows(NullPointerException.class, () -> new PlayerDisconnectedEvent(null));
    }

    @Test
    void genericSubscriptionInfersAdmissionAndNotificationResultTypes() {
        Events events = new Events() {
            @Override
            public <R, E extends Event<R>> EventSubscription subscribe(
                    Class<E> eventType, EventListener<E, R> listener) {
                return () -> { };
            }
        };

        EventSubscription admission = events.subscribe(ConnectionAdmissionEvent.class,
                event -> CompletableFuture.completedFuture(AccessDecision.allow()));
        EventSubscription notification = events.subscribe(PlayerDisconnectedEvent.class,
                event -> {
                    assertEquals("TestPlayer", event.player().username());
                    return CompletableFuture.completedFuture(null);
                });

        admission.close();
        notification.close();
    }

}

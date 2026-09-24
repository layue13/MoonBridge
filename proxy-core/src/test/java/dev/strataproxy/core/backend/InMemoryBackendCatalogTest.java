package dev.strataproxy.core.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class InMemoryBackendCatalogTest {
    private static final BackendId ID = new BackendId("lobby");
    @Test
    void everyRegistrationCreatesGenerationAndUpdateRequiresCurrentHandle() {
        InMemoryBackendCatalog catalog = new InMemoryBackendCatalog();
        BackendView first = catalog.register(registration(new BackendOwner("plugin:test", 1), 4));
        BackendView oldSnapshot = catalog.find(ID).orElseThrow();
        CapacityReservation connected = catalog.reserve(first.handle(), 2).orElseThrow();
        assertTrue(connected.commit());
        CapacityReservation oldReservation = catalog.reserve(first.handle(), 1).orElseThrow();
        BackendRegistration changed = new BackendRegistration(ID, new BackendOwner("plugin:test", 1),
                URI.create("tcp://127.0.0.1:25566"), 3, Map.of("stage", "game"), Map.of("region", "local"));
        BackendView updated = catalog.update(first.handle(), changed).orElseThrow();
        assertEquals(first.handle(), updated.handle());
        assertEquals(2, updated.connectedPlayers());

        BackendView replacement = catalog.register(registration(new BackendOwner("plugin:test", 1), 3));

        assertNotEquals(first.handle(), replacement.handle());
        assertTrue(catalog.update(first.handle(), changed).isEmpty());
        assertFalse(oldReservation.commit());
        assertFalse(catalog.remove(first.handle()));
        assertEquals(4, oldSnapshot.capacity());
        assertEquals(0, oldSnapshot.connectedPlayers());
        assertEquals(3, catalog.find(ID).orElseThrow().capacity());
        assertThrowsUnsupported(() -> oldSnapshot.tags().put("new", "value"));
        oldReservation.close();
        connected.close();
        assertEquals(3, catalog.find(ID).orElseThrow().availableUnits());
        assertTrue(catalog.update(first.handle(), new BackendRegistration(ID,
                new BackendOwner("someone-else", 1), URI.create("tcp://127.0.0.1:25567"), 1)).isEmpty());
    }

    @Test
    void connectionCountAndReservationsEnforceCapacity() {
        InMemoryBackendCatalog catalog = new InMemoryBackendCatalog();
        BackendView registered = catalog.register(registration(new BackendOwner("static", 0), 2));

        CapacityReservation connected = catalog.reserve(registered.handle(), 1).orElseThrow();
        assertTrue(connected.commit());
        CapacityReservation reservation = catalog.reserve(registered.handle(), 1).orElseThrow();
        assertTrue(catalog.reserve(registered.handle(), 1).isEmpty());
        assertEquals(0, catalog.find(ID).orElseThrow().availableUnits());

        assertTrue(reservation.commit());
        assertEquals(2, catalog.find(ID).orElseThrow().connectedPlayers());
        reservation.close();
        reservation.close();
        assertEquals(1, catalog.find(ID).orElseThrow().availableUnits());
        connected.close();
        assertEquals(2, catalog.find(ID).orElseThrow().availableUnits());
    }

    @Test
    void reservationsAreAtomicAcrossConcurrentCallers() throws Exception {
        InMemoryBackendCatalog catalog = new InMemoryBackendCatalog();
        BackendHandle handle = catalog.register(registration(new BackendOwner("static", 1), 5)).handle();
        int callers = 20;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        List<Future<Optional<CapacityReservation>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return catalog.reserve(handle, 1);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<CapacityReservation> reservations = new ArrayList<>();
            for (Future<Optional<CapacityReservation>> future : futures) {
                future.get(5, TimeUnit.SECONDS).ifPresent(reservations::add);
            }
            assertEquals(5, reservations.size());
            reservations.forEach(CapacityReservation::close);
            assertEquals(5, catalog.find(ID).orElseThrow().availableUnits());
        } finally {
            executor.shutdownNow();
        }
    }

    private static BackendRegistration registration(BackendOwner owner, int capacity) {
        return new BackendRegistration(ID, owner, URI.create("tcp://127.0.0.1:25565"), capacity,
                Map.of("stage", "hub"), Map.of("region", "local"));
    }

    private static void assertThrowsUnsupported(Runnable action) {
        try {
            action.run();
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("Expected an immutable snapshot map");
    }
}

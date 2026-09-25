package dev.strataproxy.core.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void backendRegistrationRejectsAnIgnoredUriPath() {
        assertThrows(IllegalArgumentException.class, () -> new BackendRegistration(ID,
                new BackendOwner("plugin:test", 1), URI.create("tcp://127.0.0.1:25565/world"), 4));
    }

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
        assertEquals(0, updated.connectedPlayers());

        BackendView replacement = catalog.register(registration(new BackendOwner("plugin:test", 1), 3));

        assertNotEquals(first.handle(), replacement.handle());
        assertEquals(2, replacement.connectedPlayers());
        assertEquals(1, replacement.availableUnits());
        assertTrue(catalog.reserve(replacement.handle(), 2).isEmpty());
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
    void removingAndReregisteringTheSameEndpointKeepsExistingOccupancy() {
        InMemoryBackendCatalog catalog = new InMemoryBackendCatalog();
        BackendOwner owner = new BackendOwner("plugin:test", 1);
        BackendView first = catalog.register(registration(owner, 1));
        CapacityReservation connected = catalog.reserve(first.handle(), 1).orElseThrow();
        assertTrue(connected.commit());

        assertTrue(catalog.remove(first.handle()));
        BackendView replacement = catalog.register(registration(owner, 1));
        assertEquals(1, replacement.connectedPlayers());
        assertTrue(catalog.reserve(replacement.handle(), 1).isEmpty());

        connected.close();
        assertEquals(0, catalog.find(ID).orElseThrow().connectedPlayers());
        assertEquals(1, catalog.find(ID).orElseThrow().availableUnits());
    }

    @Test
    void changingEndpointSeparatesOldConnectionsAndInvalidatesPendingClaims() {
        InMemoryBackendCatalog catalog = new InMemoryBackendCatalog();
        BackendOwner owner = new BackendOwner("plugin:test", 1);
        BackendView first = catalog.register(registration(owner, 1));
        CapacityReservation connectedToOld = catalog.reserve(first.handle(), 1).orElseThrow();
        assertTrue(connectedToOld.commit());
        BackendRegistration moved = new BackendRegistration(ID, owner,
                URI.create("tcp://127.0.0.1:25566"), 1);
        BackendView updated = catalog.update(first.handle(), moved).orElseThrow();
        assertEquals(0, updated.connectedPlayers());
        CapacityReservation pendingOnNew = catalog.reserve(updated.handle(), 1).orElseThrow();

        BackendView movedBack = catalog.update(first.handle(), registration(owner, 1)).orElseThrow();
        assertFalse(pendingOnNew.commit());
        assertEquals(1, movedBack.connectedPlayers());
        assertEquals(0, movedBack.reservedCapacity());
        assertTrue(catalog.reserve(movedBack.handle(), 1).isEmpty());

        connectedToOld.close();
        assertEquals(1, catalog.find(ID).orElseThrow().availableUnits());
        pendingOnNew.close();
    }

    @Test
    void capacityReductionInvalidatesAnOversizedPendingClaim() {
        InMemoryBackendCatalog catalog = new InMemoryBackendCatalog();
        BackendOwner owner = new BackendOwner("plugin:test", 1);
        BackendView first = catalog.register(registration(owner, 2));
        CapacityReservation pending = catalog.reserve(first.handle(), 2).orElseThrow();

        BackendView reduced = catalog.update(first.handle(), registration(owner, 1)).orElseThrow();
        assertEquals(1, reduced.capacity());
        assertFalse(pending.commit());
        assertEquals(0, catalog.find(ID).orElseThrow().connectedPlayers());
        assertEquals(0, catalog.find(ID).orElseThrow().reservedCapacity());
        assertEquals(1, catalog.find(ID).orElseThrow().availableUnits());
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

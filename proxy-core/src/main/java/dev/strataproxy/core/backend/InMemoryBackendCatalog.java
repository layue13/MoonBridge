package dev.strataproxy.core.backend;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.net.URI;

/** Thread-safe in-process catalog with generation-scoped updates and reservations. */
public final class InMemoryBackendCatalog implements BackendCatalog {
    private final Map<BackendId, Entry> entries = new LinkedHashMap<>();
    private final Map<Endpoint, Occupancy> occupancies = new HashMap<>();
    private long nextGeneration;
    private long nextReservationId;

    @Override
    public synchronized BackendView register(BackendRegistration registration) {
        Entry current = entries.get(registration.id());
        if (current == null) {
            Entry created = new Entry(registration, new BackendHandle(registration.id(), nextGeneration()),
                    occupancy(registration));
            entries.put(registration.id(), created);
            created.occupancy.registered = true;
            return created.view();
        }

        if (!current.owner.id().equals(registration.owner().id())) {
            throw new IllegalStateException("Backend is owned by " + current.owner.id());
        }
        if (registration.owner().instanceGeneration() < current.owner.instanceGeneration()) {
            throw new IllegalArgumentException("Stale owner instance generation");
        }

        // A fresh registration always gets a new token, even for the same owner generation.
        // Async work holding the previous handle can no longer mutate this definition.
        Entry replacement = new Entry(registration, new BackendHandle(registration.id(), nextGeneration()),
                occupancy(registration));
        entries.put(registration.id(), replacement);
        if (current.occupancy != replacement.occupancy) {
            current.occupancy.registered = false;
            replacement.occupancy.registered = true;
            retireIfVacant(current.occupancy);
        }
        return replacement.view();
    }

    @Override
    public synchronized Optional<BackendView> update(BackendHandle handle, BackendRegistration registration) {
        Entry entry = current(handle);
        if (entry == null || !registration.id().equals(handle.id()) || !registration.owner().equals(entry.owner)) {
            return Optional.empty();
        }
        if (!entry.definition.address().equals(registration.address())) {
            // Pending claims belong to the previous endpoint and must not commit on the new one.
            entry.reservations.clear();
            entry.reservedUnits = 0;
            Occupancy previous = entry.occupancy;
            entry.occupancy = occupancy(registration);
            previous.registered = false;
            entry.occupancy.registered = true;
            retireIfVacant(previous);
        }
        entry.definition = registration;
        return Optional.of(entry.view());
    }

    @Override
    public synchronized boolean remove(BackendHandle handle) {
        Entry current = current(handle);
        if (current == null) {
            return false;
        }
        entries.remove(handle.id());
        current.occupancy.registered = false;
        retireIfVacant(current.occupancy);
        return true;
    }

    @Override
    public synchronized int removeOwner(BackendOwner owner) {
        int removed = 0;
        var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (!entry.owner.equals(owner)) continue;
            iterator.remove();
            entry.occupancy.registered = false;
            retireIfVacant(entry.occupancy);
            removed++;
        }
        return removed;
    }

    @Override
    public synchronized Optional<BackendView> find(BackendId id) {
        Entry entry = entries.get(id);
        return entry == null ? Optional.empty() : Optional.of(entry.view());
    }

    @Override
    public synchronized List<BackendView> snapshot() {
        List<BackendView> views = new ArrayList<>(entries.size());
        entries.values().forEach(entry -> views.add(entry.view()));
        return List.copyOf(views);
    }

    @Override
    public synchronized Optional<CapacityReservation> reserve(BackendHandle handle, int units) {
        if (units <= 0) {
            throw new IllegalArgumentException("Reservation units must be positive");
        }
        Entry entry = current(handle);
        if (entry == null || units > entry.definition.capacity()
                - entry.occupancy.connectedPlayers - entry.reservedCapacity()) {
            return Optional.empty();
        }
        long reservationId = nextReservationId();
        entry.reservations.put(reservationId, units);
        entry.reservedUnits += units;
        Occupancy reservedOccupancy = entry.occupancy;
        return Optional.of(new CapacityReservation(entry.view(), units,
                () -> commit(handle, entry, reservedOccupancy, reservationId),
                () -> release(entry, reservationId),
                () -> disconnect(reservedOccupancy, units)));
    }

    private synchronized boolean commit(BackendHandle handle, Entry reservedEntry,
                                        Occupancy reservedOccupancy, long reservationId) {
        if (current(handle) != reservedEntry || reservedEntry.occupancy != reservedOccupancy) return false;
        Integer units = reservedEntry.reservations.remove(reservationId);
        if (units == null) return false;
        reservedEntry.reservedUnits -= units;
        if (units > reservedEntry.definition.capacity() - reservedOccupancy.connectedPlayers
                - reservedEntry.reservedCapacity()) return false;
        reservedOccupancy.connectedPlayers += units;
        return true;
    }

    private synchronized void release(Entry reservedEntry, long reservationId) {
        Integer units = reservedEntry.reservations.remove(reservationId);
        if (units != null) reservedEntry.reservedUnits -= units;
    }

    private synchronized void disconnect(Occupancy occupancy, int units) {
        occupancy.connectedPlayers -= units;
        if (occupancy.connectedPlayers < 0) {
            throw new IllegalStateException("Connected capacity underflow");
        }
        retireIfVacant(occupancy);
    }

    private Occupancy occupancy(BackendRegistration registration) {
        Endpoint endpoint = new Endpoint(registration.id(), registration.address());
        return occupancies.computeIfAbsent(endpoint, Occupancy::new);
    }

    private void retireIfVacant(Occupancy occupancy) {
        if (!occupancy.registered && occupancy.connectedPlayers == 0) {
            occupancies.remove(occupancy.endpoint, occupancy);
        }
    }

    private Entry current(BackendHandle handle) {
        Entry entry = entries.get(handle.id());
        return entry != null && entry.handle.equals(handle) ? entry : null;
    }

    private long nextGeneration() {
        if (nextGeneration == Long.MAX_VALUE) {
            throw new IllegalStateException("Catalog generation exhausted");
        }
        return ++nextGeneration;
    }

    private long nextReservationId() {
        if (nextReservationId == Long.MAX_VALUE) {
            throw new IllegalStateException("Reservation id exhausted");
        }
        return ++nextReservationId;
    }

    private static final class Entry {
        private final BackendHandle handle;
        private final BackendOwner owner;
        private BackendRegistration definition;
        private Occupancy occupancy;
        private final Map<Long, Integer> reservations = new LinkedHashMap<>();
        private int reservedUnits;

        private Entry(BackendRegistration definition, BackendHandle handle, Occupancy occupancy) {
            this.definition = definition;
            this.owner = definition.owner();
            this.handle = handle;
            this.occupancy = occupancy;
        }

        private int reservedCapacity() {
            return reservedUnits;
        }

        private BackendView view() {
            return new BackendView(handle, owner, definition.address(), definition.capacity(), occupancy.connectedPlayers,
                    reservedCapacity(), definition.tags(), definition.metadata());
        }
    }

    private record Endpoint(BackendId id, URI address) { }

    private static final class Occupancy {
        private final Endpoint endpoint;
        private boolean registered;
        private int connectedPlayers;

        private Occupancy(Endpoint endpoint) {
            this.endpoint = endpoint;
        }
    }
}

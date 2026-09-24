package dev.strataproxy.core.backend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Thread-safe in-process catalog with generation-scoped updates and reservations. */
public final class InMemoryBackendCatalog implements BackendCatalog {
    private final Map<BackendId, Entry> entries = new LinkedHashMap<>();
    private long nextGeneration;
    private long nextReservationId;

    @Override
    public synchronized BackendView register(BackendRegistration registration) {
        Entry current = entries.get(registration.id());
        if (current == null) {
            Entry created = new Entry(registration, new BackendHandle(registration.id(), nextGeneration()));
            entries.put(registration.id(), created);
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
        Entry replacement = new Entry(registration, new BackendHandle(registration.id(), nextGeneration()));
        entries.put(registration.id(), replacement);
        return replacement.view();
    }

    @Override
    public synchronized Optional<BackendView> update(BackendHandle handle, BackendRegistration registration) {
        Entry entry = current(handle);
        if (entry == null || !registration.id().equals(handle.id()) || !registration.owner().equals(entry.owner)) {
            return Optional.empty();
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
        return true;
    }

    @Override
    public synchronized int removeOwner(BackendOwner owner) {
        int before = entries.size();
        entries.entrySet().removeIf(entry -> entry.getValue().owner.equals(owner));
        return before - entries.size();
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
        if (entry == null || units > entry.definition.capacity() - entry.connectedPlayers - entry.reservedCapacity()) {
            return Optional.empty();
        }
        long reservationId = nextReservationId();
        entry.reservations.put(reservationId, units);
        return Optional.of(new CapacityReservation(handle, units,
                () -> commit(handle, entry, reservationId),
                () -> release(entry, reservationId),
                () -> disconnect(entry, units)));
    }

    private synchronized boolean commit(BackendHandle handle, Entry reservedEntry, long reservationId) {
        if (current(handle) != reservedEntry) return false;
        Integer units = reservedEntry.reservations.remove(reservationId);
        if (units == null) return false;
        reservedEntry.connectedPlayers += units;
        return true;
    }

    private synchronized void release(Entry reservedEntry, long reservationId) {
        reservedEntry.reservations.remove(reservationId);
    }

    private synchronized void disconnect(Entry reservedEntry, int units) {
        reservedEntry.connectedPlayers -= units;
        if (reservedEntry.connectedPlayers < 0) {
            throw new IllegalStateException("Connected capacity underflow");
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
        private int connectedPlayers;
        private final Map<Long, Integer> reservations = new LinkedHashMap<>();

        private Entry(BackendRegistration definition, BackendHandle handle) {
            this.definition = definition;
            this.owner = definition.owner();
            this.handle = handle;
        }

        private int reservedCapacity() {
            long total = reservations.values().stream().mapToLong(Integer::longValue).sum();
            return Math.toIntExact(total);
        }

        private BackendView view() {
            return new BackendView(handle, owner, definition.address(), definition.capacity(), connectedPlayers,
                    reservedCapacity(), definition.tags(), definition.metadata());
        }
    }
}

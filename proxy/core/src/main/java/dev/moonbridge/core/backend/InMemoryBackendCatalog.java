package dev.moonbridge.core.backend;

import dev.moonbridge.core.log.ConnectionLog;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Thread-safe registration directory with generation-scoped mutations. */
public final class InMemoryBackendCatalog implements BackendCatalog {
    private final Map<BackendId, Entry> entries = new LinkedHashMap<>();
    private long nextGeneration;

    // Changes are logged after the catalog lock is released: routing reads the catalog on every login.

    @Override
    public BackendView register(BackendRegistration registration) {
        URI previous;
        BackendView registered;
        synchronized (this) {
            Entry current = entries.get(registration.id());
            if (current != null) {
                if (!current.owner.id().equals(registration.owner().id())) {
                    throw new IllegalStateException("Backend is owned by " + current.owner.id());
                }
                if (registration.owner().instanceGeneration() < current.owner.instanceGeneration()) {
                    throw new IllegalArgumentException("Stale owner instance generation");
                }
            }
            previous = current == null ? null : current.definition.address();
            // A new registration invalidates handles held by earlier asynchronous work.
            Entry replacement = new Entry(registration, new BackendHandle(registration.id(), nextGeneration()));
            entries.put(registration.id(), replacement);
            registered = replacement.view();
        }
        String name = registration.id().value();
        if (previous == null) {
            ConnectionLog.serverRegistered(name, registration.address(), registration.owner().id());
        } else if (!previous.equals(registration.address())) {
            ConnectionLog.serverMoved(name, previous, registration.address(), registration.owner().id());
        }
        return registered;
    }

    @Override
    public Optional<BackendView> update(BackendHandle handle, BackendRegistration registration) {
        URI previous;
        BackendView updated;
        synchronized (this) {
            Entry entry = current(handle);
            if (entry == null || !registration.id().equals(handle.id()) || !registration.owner().equals(entry.owner)) {
                return Optional.empty();
            }
            previous = entry.definition.address();
            entry.definition = registration;
            updated = entry.view();
        }
        if (!previous.equals(registration.address())) {
            ConnectionLog.serverMoved(handle.id().value(), previous, registration.address(), registration.owner().id());
        }
        return Optional.of(updated);
    }

    @Override
    public boolean remove(BackendHandle handle) {
        Entry removed;
        synchronized (this) {
            removed = current(handle);
            if (removed == null) return false;
            entries.remove(handle.id());
        }
        ConnectionLog.serverUnregistered(handle.id().value(), removed.owner.id());
        return true;
    }

    @Override
    public int removeOwner(BackendOwner owner) {
        List<BackendId> removed = new ArrayList<>();
        synchronized (this) {
            var iterator = entries.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (!entry.getValue().owner.equals(owner)) continue;
                iterator.remove();
                removed.add(entry.getKey());
            }
        }
        removed.forEach(id -> ConnectionLog.serverUnregistered(id.value(), owner.id()));
        return removed.size();
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

    private Entry current(BackendHandle handle) {
        Entry entry = entries.get(handle.id());
        return entry != null && entry.handle.equals(handle) ? entry : null;
    }

    private long nextGeneration() {
        if (nextGeneration == Long.MAX_VALUE) throw new IllegalStateException("Catalog generation exhausted");
        return ++nextGeneration;
    }

    private static final class Entry {
        private final BackendHandle handle;
        private final BackendOwner owner;
        private BackendRegistration definition;

        private Entry(BackendRegistration definition, BackendHandle handle) {
            this.definition = definition;
            this.owner = definition.owner();
            this.handle = handle;
        }

        private BackendView view() {
            return new BackendView(handle, owner, definition.address(), definition.tags(), definition.metadata());
        }
    }
}

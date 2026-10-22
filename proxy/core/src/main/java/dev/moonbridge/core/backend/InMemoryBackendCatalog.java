package dev.moonbridge.core.backend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Thread-safe registration directory with generation-scoped mutations. */
public final class InMemoryBackendCatalog implements BackendCatalog {
    private final Map<BackendId, Entry> entries = new LinkedHashMap<>();
    private long nextGeneration;

    @Override
    public synchronized BackendView register(BackendRegistration registration) {
        Entry current = entries.get(registration.id());
        if (current != null) {
            if (!current.owner.id().equals(registration.owner().id())) {
                throw new IllegalStateException("Backend is owned by " + current.owner.id());
            }
            if (registration.owner().instanceGeneration() < current.owner.instanceGeneration()) {
                throw new IllegalArgumentException("Stale owner instance generation");
            }
        }
        // A new registration invalidates handles held by earlier asynchronous work.
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
        if (current(handle) == null) return false;
        entries.remove(handle.id());
        return true;
    }

    @Override
    public synchronized int removeOwner(BackendOwner owner) {
        int removed = 0;
        var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            if (!iterator.next().getValue().owner.equals(owner)) continue;
            iterator.remove();
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

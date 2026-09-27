package dev.moonbridge.luckperms;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Tracks active sessions and in-flight LP loads per UUID; LP cleanup is UUID-scoped, not session-scoped. */
final class ApiUserLeaseTracker<T> {
    private final Map<UUID, State<T>> states = new HashMap<>();
    private final Consumer<? super T> cleanup;

    ApiUserLeaseTracker(Consumer<? super T> cleanup) {
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
    }

    synchronized Lease<T> acquire(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        State<T> state = states.computeIfAbsent(playerId, ignored -> new State<>());
        state.owners++;
        state.pendingLoads++;
        return new Lease<>(this, playerId, state);
    }

    private synchronized void loaded(Lease<T> lease, T apiUser) {
        Objects.requireNonNull(apiUser, "apiUser");
        if (lease.loadFinished) return;
        lease.loadFinished = true;
        State<T> state = lease.state;
        state.pendingLoads--;
        // cleanupUser unregisters UUID-wide usage. Keep any successful wrapper until the final owner exits.
        state.apiUser = apiUser;
        cleanupIfIdle(lease.playerId, state);
    }

    private synchronized void failed(Lease<T> lease) {
        if (lease.loadFinished) return;
        lease.loadFinished = true;
        State<T> state = lease.state;
        state.pendingLoads--;
        cleanupIfIdle(lease.playerId, state);
    }

    private synchronized void close(Lease<T> lease) {
        if (!lease.ownerOpen) return;
        lease.ownerOpen = false;
        State<T> state = lease.state;
        state.owners--;
        cleanupIfIdle(lease.playerId, state);
    }

    private void cleanupIfIdle(UUID playerId, State<T> state) {
        if (state.owners != 0 || state.pendingLoads != 0) return;
        states.remove(playerId, state);
        if (state.apiUser != null) cleanup.accept(state.apiUser);
    }

    static final class Lease<T> implements AutoCloseable {
        private final ApiUserLeaseTracker<T> tracker;
        private final UUID playerId;
        private final State<T> state;
        // All mutable fields are guarded by tracker synchronization.
        private boolean ownerOpen = true;
        private boolean loadFinished;

        private Lease(ApiUserLeaseTracker<T> tracker, UUID playerId, State<T> state) {
            this.tracker = tracker;
            this.playerId = playerId;
            this.state = state;
        }

        /** Marks the successful loadUser result. Must be called even if the session has already closed. */
        void loaded(T apiUser) { tracker.loaded(this, apiUser); }

        /** Marks a failed loadUser operation. */
        void failed() { tracker.failed(this); }

        /** Releases this session owner; an unfinished load remains tracked until it succeeds or fails. */
        @Override public void close() { tracker.close(this); }
    }

    private static final class State<T> {
        private int owners;
        private int pendingLoads;
        private T apiUser;
    }
}

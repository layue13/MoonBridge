package dev.strataproxy.core.backend;

import java.util.function.BooleanSupplier;

/** One capacity claim that can atomically become a connected player. */
public final class CapacityReservation implements AutoCloseable {
    private final BackendView backendView;
    private final int units;
    private final BooleanSupplier commit;
    private final Runnable release;
    private final Runnable disconnect;
    private volatile State state = State.RESERVED;

    CapacityReservation(BackendView backendView, int units, BooleanSupplier commit,
                        Runnable release, Runnable disconnect) {
        this.backendView = backendView;
        this.units = units;
        this.commit = commit;
        this.release = release;
        this.disconnect = disconnect;
    }

    /** Address and definition atomically selected with this capacity claim. */
    public BackendView backendView() {
        return backendView;
    }

    public int units() {
        return units;
    }

    /** Converts the reservation to connected capacity without releasing the slot. */
    public synchronized boolean commit() {
        if (state != State.RESERVED) return false;
        if (!commit.getAsBoolean()) {
            state = State.CLOSED;
            release.run();
            return false;
        }
        state = State.CONNECTED;
        return true;
    }

    @Override
    public synchronized void close() {
        if (state == State.CLOSED) return;
        State previous = state;
        state = State.CLOSED;
        if (previous == State.RESERVED) release.run();
        else disconnect.run();
    }

    public boolean isClosed() {
        return state == State.CLOSED;
    }

    private enum State { RESERVED, CONNECTED, CLOSED }
}

package dev.moonbridge.core.plugin;

import dev.moonbridge.api.Plugin;


/** The plugin host's coarse state, shared by the host and the services it owns. */
final class HostLifecycle {
    enum State { LOADING, ENABLED, CLOSED }

    private volatile State state = State.LOADING;

    State get() { return state; }
    boolean enabled() { return state == State.ENABLED; }
    boolean loading() { return state == State.LOADING; }
    boolean closed() { return state == State.CLOSED; }
    void set(State next) { state = next; }

    void require(State expected) {
        if (state != expected) {
            throw new IllegalStateException("Plugin host state is " + state + "; expected " + expected);
        }
    }
}

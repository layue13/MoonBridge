package dev.moonbridge.api;

/** Opaque generation-bound handle for a plugin-owned dynamic backend. */
public interface ServerRegistration {
    /**
     * Replaces this registration's configuration; the definition must keep the registered name.
     * Stale handles are rejected with {@link IllegalStateException}.
     */
    void update(ServerDefinition definition);

    /** Removes this registration from the proxy directory. Stale handles are rejected. */
    void unregister();
}

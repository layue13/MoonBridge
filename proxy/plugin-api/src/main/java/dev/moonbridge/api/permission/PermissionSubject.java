package dev.moonbridge.api.permission;

import dev.moonbridge.api.PlayerView;

/** Per-connection permission state owned by the proxy until that exact connection is released. */
public interface PermissionSubject extends AutoCloseable {
    /** Checks must use local/cached data and never perform I/O; the proxy may call this on a network event loop. */
    PermissionDecision check(String node, PermissionContext context);

    /**
     * Updates the live player snapshot after a committed state change such as a server transfer. This method must be
     * nonblocking and perform no I/O because the proxy may call it on a network event loop.
     */
    void update(PlayerView player);

    /**
     * Releases resources for this connection. The proxy invokes this asynchronously, including for a subject whose
     * connection ended while it was still being opened.
     */
    @Override
    void close();
}

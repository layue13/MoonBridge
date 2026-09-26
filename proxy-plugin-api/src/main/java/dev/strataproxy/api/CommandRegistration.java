package dev.strataproxy.api;

/** A command registration owned by one plugin. Unregistration is idempotent. */
public interface CommandRegistration extends AutoCloseable {
    void unregister();

    @Override default void close() {
        unregister();
    }
}

package dev.moonbridge.api;

/** Handle for a service a plugin registered. Disabling the plugin unregisters it as well. */
public interface ServiceRegistration extends AutoCloseable {
    /** Withdraws the service; later lookups no longer find it. Repeated calls have no effect. */
    void unregister();

    @Override default void close() { unregister(); }
}

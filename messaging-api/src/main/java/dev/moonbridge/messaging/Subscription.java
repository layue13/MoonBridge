package dev.moonbridge.messaging;

/** Handle for one local listener or request handler. Closing it is idempotent and unregisters only this handle. */
public interface Subscription extends AutoCloseable {
    @Override
    void close();
}

package dev.moonbridge.messaging;

/**
 * Messaging facade owned by one plugin scope. Closing the facade removes only this owner's subscriptions and
 * completes its unfinished public operations with {@link MessagingException.Code#CLOSED}; queued callbacks
 * check the scope before invoking plugin code.
 */
public interface Messaging extends AutoCloseable {
    /** Gets a channel facade for an ASCII {@code namespace:name} channel. */
    MessageChannel channel(String name);

    @Override
    void close();
}

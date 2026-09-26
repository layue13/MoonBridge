package dev.strataproxy.api.event;

/** A handle for a registered event listener. Closing it more than once has no additional effect. */
public interface EventSubscription extends AutoCloseable {
    @Override
    void close();
}

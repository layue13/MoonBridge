package dev.strataproxy.plugin.event;

/**
 * Callback for proxy events.
 *
 * @param <T> event type handled by this listener
 */
@FunctionalInterface
public interface EventListener<T extends ProxyEvent> {
    /**
     * Handles a published event.
     *
     * @param event event instance
     */
    void handle(T event);
}

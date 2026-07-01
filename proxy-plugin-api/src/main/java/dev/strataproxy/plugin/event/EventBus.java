package dev.strataproxy.plugin.event;

/**
 * Synchronous event bus exposed to plugins.
 */
public interface EventBus {
    /**
     * Subscribes a listener to a specific event type.
     *
     * @param eventType event class to receive
     * @param listener callback invoked for matching events
     * @param <T> event type
     * @return handle that removes the subscription when closed
     */
    <T extends ProxyEvent> AutoCloseable subscribe(Class<T> eventType, EventListener<T> listener);

    /**
     * Publishes an event to registered listeners.
     *
     * @param event event instance
     */
    void publish(ProxyEvent event);
}

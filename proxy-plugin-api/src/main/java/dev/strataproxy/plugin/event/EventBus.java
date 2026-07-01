package dev.strataproxy.plugin.event;

public interface EventBus {
    <T extends ProxyEvent> AutoCloseable subscribe(Class<T> eventType, EventListener<T> listener);

    void publish(ProxyEvent event);
}

package dev.strataproxy.plugin.event;

@FunctionalInterface
public interface EventListener<T extends ProxyEvent> {
    void handle(T event);
}

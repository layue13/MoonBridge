package dev.strataproxy.command;

import dev.strataproxy.plugin.event.EventBus;
import dev.strataproxy.plugin.event.EventListener;
import dev.strataproxy.plugin.event.ProxyEvent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

public final class SimpleEventBus implements EventBus {
    private final Logger logger;
    private final Map<Class<?>, CopyOnWriteArrayList<EventListener<?>>> listeners = new ConcurrentHashMap<>();

    public SimpleEventBus() {
        this(Logger.getLogger(SimpleEventBus.class.getName()));
    }

    public SimpleEventBus(Logger logger) {
        this.logger = logger == null ? Logger.getLogger(SimpleEventBus.class.getName()) : logger;
    }

    @Override
    public <T extends ProxyEvent> AutoCloseable subscribe(Class<T> eventType, EventListener<T> listener) {
        listeners.computeIfAbsent(eventType, ignored -> new CopyOnWriteArrayList<>()).add(listener);
        return () -> {
            var registered = listeners.getOrDefault(eventType, new CopyOnWriteArrayList<>());
            registered.remove(listener);
        };
    }

    @Override
    public void publish(ProxyEvent event) {
        if (event == null) {
            return;
        }
        for (var entry : listeners.entrySet()) {
            if (entry.getKey().isAssignableFrom(event.getClass())) {
                notify(entry.getValue(), event, logger);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void notify(List<EventListener<?>> listeners, ProxyEvent event, Logger logger) {
        for (var listener : listeners) {
            try {
                ((EventListener) listener).handle(event);
            } catch (RuntimeException exception) {
                logger.warning("Plugin event listener failed for " + event.getClass().getSimpleName() + ": " + exception.getMessage());
            }
        }
    }
}

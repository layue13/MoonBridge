package dev.moonbridge.core.plugin;

import dev.moonbridge.api.event.Event;
import dev.moonbridge.api.event.EventListener;
import dev.moonbridge.api.event.EventSubscription;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

    final class EventRegistration<E extends Event<R>, R>
            implements EventSubscription, AsyncEventDispatcher.EventHandler<R> {
        final PluginContextImpl context;
        private final Class<E> eventType;
        private final EventListener<? super E, R> listener;
        private final AtomicBoolean active = new AtomicBoolean(true);

        EventRegistration(PluginContextImpl context, Class<E> eventType,
                                  EventListener<? super E, R> listener) {
            this.context = context;
            this.eventType = eventType;
            this.listener = listener;
        }

        Class<E> eventType() { return eventType; }
        @Override public boolean active() { return isActive(); }
        @Override public CompletionStage<R> handle(Event<?> event) {
            return listener.onEvent(eventType.cast(event));
        }
        boolean isActive() { return active.get() && context.active; }
        void requireActive() {
            if (!isActive()) throw new IllegalStateException("Transfer listener owner is no longer active");
        }
        @Override public void close() { active.set(false); }
    }

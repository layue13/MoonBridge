package dev.moonbridge.core.event;

import dev.moonbridge.api.event.Event;

import java.util.concurrent.CompletionStage;

/** Internal boundary between session transitions and the plugin event runtime. */
public interface EventDispatcher {
    boolean hasSubscribers(Class<?> eventType);

    <R> CompletionStage<R> dispatch(Event<R> event);
}

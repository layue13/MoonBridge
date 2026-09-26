package dev.strataproxy.core.event;

import dev.strataproxy.api.event.Event;

import java.util.concurrent.CompletionStage;

/** Internal boundary between session transitions and the plugin event runtime. */
public interface EventDispatcher {
    boolean hasSubscribers(Class<?> eventType);

    <R> CompletionStage<R> dispatch(Event<R> event);
}

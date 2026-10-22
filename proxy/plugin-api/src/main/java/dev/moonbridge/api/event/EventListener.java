package dev.moonbridge.api.event;

import java.util.concurrent.CompletionStage;

/** Handles an event asynchronously. Callbacks run off the proxy I/O event loop. */
@FunctionalInterface
public interface EventListener<E extends Event<R>, R> {
    CompletionStage<R> onEvent(E event);
}

package dev.strataproxy.core.event;

import dev.strataproxy.api.AccessDecision;
import dev.strataproxy.api.event.AccessEvent;
import dev.strataproxy.api.event.NotificationEvent;

import java.util.concurrent.CompletionStage;

/** Internal boundary between session transitions and the plugin event runtime. */
public interface EventDispatcher {
    boolean hasSubscribers(Class<?> eventType);

    CompletionStage<AccessDecision> check(AccessEvent event);

    /** Enqueues a best-effort notification without invoking plugins on the calling thread. */
    void publish(NotificationEvent event);
}

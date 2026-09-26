package dev.strataproxy.api.event;

import java.util.function.Consumer;

/**
 * Typed event subscriptions available to plugins.
 *
 * <p>Listeners may be registered from {@code onLoad} or {@code onEnable}; registration is frozen
 * once enabling completes. Only concrete core event classes may be subscribed to; family
 * interfaces are not catch-all subscriptions. Subscriptions are delivered in registration order. A plugin may close a
 * subscription at any time. The host closes all of a plugin's subscriptions when it is disabled or
 * fails to load or enable.
 *
 * <p>Access listeners run asynchronously off the proxy I/O event loop. Their decisions are
 * aggregated under the proxy's deadline; exceptions, failed stages, and missing decisions fail
 * closed. Notification listeners are best effort: they run on bounded workers off the I/O event
 * loop, listener exceptions are isolated, and notification delivery never blocks session progress.
 * Notifications for the same player are ordered among events that are delivered. Queue overload
 * may drop notifications and is logged by the host.
 */
public interface Events {
    /** Subscribe to a best-effort session notification. */
    <E extends NotificationEvent> EventSubscription subscribe(
            Class<E> eventType, Consumer<? super E> listener);

    /** Subscribe to an asynchronous access decision event. */
    <E extends AccessEvent> EventSubscription subscribe(
            Class<E> eventType, AccessListener<? super E> listener);
}

package dev.moonbridge.api.event;

/**
 * Typed event subscriptions available to plugins.
 *
 * <p>Listeners may be registered from {@code onLoad} or {@code onEnable}; registration is frozen
 * once enabling completes. Only concrete core event classes may be subscribed to. Subscriptions
 * are delivered in registration order. A plugin may close a subscription at any time. The host
 * closes all of a plugin's subscriptions when it is disabled or fails to load or enable.
 *
 * <p>All listeners run asynchronously off the proxy I/O event loop, and every event chain is
 * subject to the same configured deadline, including time waiting in the dispatch queue.
 * Each concrete event defines its result aggregation.
 * Admission events aggregate {@code AccessDecision} results by allowing access when every listener
 * allows it; the first denial denies access. With no listeners, admission is allowed. A listener
 * returning a null stage or result, failing, or timing out fails closed. Notification events use
 * {@link Void}; their listener results are ignored.
 *
 * <p>Notifications are best effort. The host serializes delivery through each listener's
 * completion, isolates listener exceptions, and keeps at most 128 notifications queued. If an
 * event's deadline expires, remaining listeners for that event are skipped and delivery continues
 * with the next queued event. A notification with no registered listeners is skipped by the
 * runtime. Queue overflow may drop notifications and is logged by the host.
 */
public interface Events {
    /** Subscribe to a core-published event with a typed asynchronous result. */
    <R, E extends Event<R>> EventSubscription subscribe(
            Class<E> eventType, EventListener<E, R> listener);
}

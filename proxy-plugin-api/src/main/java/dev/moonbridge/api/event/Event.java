package dev.moonbridge.api.event;

/**
 * A core-published event whose result type describes the response accepted from its listeners.
 * Concrete event types define how those responses are aggregated. Plugins can subscribe to core
 * events but cannot publish them through this API.
 *
 * @param <R> the listener result type, or {@link Void} for notifications
 */
public sealed interface Event<R>
        permits ConnectionAdmissionEvent, PlayerAdmissionEvent, ServerConnectedEvent,
                PlayerDisconnectedEvent {
}

package dev.strataproxy.plugin.event;

/**
 * Event emitted after the proxy has started accepting work.
 */
public record ProxyStartedEvent() implements ProxyEvent {
}

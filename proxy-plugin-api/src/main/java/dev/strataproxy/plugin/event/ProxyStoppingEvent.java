package dev.strataproxy.plugin.event;

/**
 * Event emitted while the proxy is shutting down.
 */
public record ProxyStoppingEvent() implements ProxyEvent {
}

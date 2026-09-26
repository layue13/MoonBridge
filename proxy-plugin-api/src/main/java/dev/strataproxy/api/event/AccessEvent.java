package dev.strataproxy.api.event;

/** An event for which plugins may asynchronously allow or deny access. */
public sealed interface AccessEvent permits ConnectionEvent, LoginEvent {
}

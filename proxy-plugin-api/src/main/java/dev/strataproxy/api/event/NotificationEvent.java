package dev.strataproxy.api.event;

/** A best-effort notification that reports a published player-session change. */
public sealed interface NotificationEvent permits ServerConnectedEvent, PlayerDisconnectedEvent {
}

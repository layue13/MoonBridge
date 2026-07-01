package dev.strataproxy.plugin.event;

public record PlayerCommandEvent(String playerName, String commandLine) implements ProxyEvent {
}

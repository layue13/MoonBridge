package dev.strataproxy.plugin.event;

/**
 * Event emitted after a player command line is observed by the proxy.
 *
 * @param playerName player that sent the command
 * @param commandLine raw command line
 */
public record PlayerCommandEvent(String playerName, String commandLine) implements ProxyEvent {
}

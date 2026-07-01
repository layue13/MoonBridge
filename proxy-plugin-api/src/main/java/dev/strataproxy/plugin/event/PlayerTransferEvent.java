package dev.strataproxy.plugin.event;

public record PlayerTransferEvent(String playerName, String sourceServer, String targetServer, boolean success, String outcome)
        implements ProxyEvent {
}

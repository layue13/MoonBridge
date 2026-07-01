package dev.strataproxy.plugin.service;

public record PlayerTransfer(boolean success, String outcome, String playerName, String sourceServer, String targetServer) {
}

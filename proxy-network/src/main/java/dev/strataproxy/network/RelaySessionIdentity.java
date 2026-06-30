package dev.strataproxy.network;

final class RelaySessionIdentity {
    private final String remoteAddress;
    private volatile String playerName;

    RelaySessionIdentity(String remoteAddress) {
        this.remoteAddress = remoteAddress == null ? "" : remoteAddress;
    }

    String remoteAddress() {
        return remoteAddress;
    }

    String playerName() {
        return playerName == null ? "" : playerName;
    }

    void playerName(String playerName) {
        this.playerName = playerName == null ? "" : playerName;
    }
}

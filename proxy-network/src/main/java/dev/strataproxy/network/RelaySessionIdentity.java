package dev.strataproxy.network;

final class RelaySessionIdentity {
    private final String remoteAddress;
    private volatile String playerName;
    private volatile MinecraftSessionVerifier.GameProfile profile;
    private volatile MinecraftLoginStart.ChatSessionKey chatSessionKey;

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

    MinecraftSessionVerifier.GameProfile profile() {
        return profile;
    }

    void profile(MinecraftSessionVerifier.GameProfile profile) {
        this.profile = profile;
        if (profile != null && !profile.name().isBlank()) {
            playerName(profile.name());
        }
    }

    MinecraftLoginStart.ChatSessionKey chatSessionKey() {
        return chatSessionKey;
    }

    void chatSessionKey(MinecraftLoginStart.ChatSessionKey chatSessionKey) {
        this.chatSessionKey = chatSessionKey;
    }
}

package dev.strataproxy.backend.api;

import java.util.Arrays;

/** Immutable proxy-to-backend plugin message. */
public final class BackendMessage {
    private final BackendPlayer player;
    private final String channel;
    private final byte[] payload;

    public BackendMessage(BackendPlayer player, String channel, byte[] payload) {
        if (player == null) throw new IllegalArgumentException("player must not be null");
        if (channel == null || channel.trim().isEmpty()) throw new IllegalArgumentException("channel must not be blank");
        this.player = player;
        this.channel = channel.trim();
        this.payload = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
    }

    public BackendPlayer player() { return player; }
    public String channel() { return channel; }
    public byte[] payload() { return Arrays.copyOf(payload, payload.length); }
}

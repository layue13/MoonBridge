package dev.strataproxy.backend.api;

import java.util.Arrays;

/** Immutable message emitted by the StrataProxy backend-message broker. */
public final class BackendMessage {
    private final String messageId;
    private final String correlationId;
    private final String source;
    private final String channel;
    private final byte[] payload;

    public BackendMessage(String messageId, String correlationId, String source, String channel, byte[] payload) {
        if (messageId == null || messageId.trim().isEmpty()) throw new IllegalArgumentException("messageId must not be blank");
        if (channel == null || channel.trim().isEmpty()) throw new IllegalArgumentException("channel must not be blank");
        this.messageId = messageId.trim();
        this.correlationId = correlationId == null ? "" : correlationId.trim();
        this.source = source == null ? "" : source.trim();
        this.channel = channel.trim();
        this.payload = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
    }

    public String messageId() { return messageId; }
    public String correlationId() { return correlationId; }
    public String source() { return source; }
    public String channel() { return channel; }
    public byte[] payload() { return Arrays.copyOf(payload, payload.length); }
}

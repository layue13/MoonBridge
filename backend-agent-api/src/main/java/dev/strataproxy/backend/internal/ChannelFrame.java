package dev.strataproxy.backend.internal;

/** Internal, versioned agent-stream frame shared by platform adapters and the proxy. */
public final class ChannelFrame {
    public static final byte SUBSCRIBE = 1;
    public static final byte UNSUBSCRIBE = 2;
    public static final byte PUBLISH = 3;
    public static final byte ACK = 4;
    public static final byte MESSAGE = 5;
    public static final byte RESULT = 6;

    public final byte type;
    public final String requestId;
    public final String messageId;
    public final String correlationId;
    public final String idempotencyKey;
    public final String source;
    public final String channel;
    public final String mode;
    public final String outcome;
    public final byte[] payload;

    public ChannelFrame(byte type, String requestId, String messageId, String correlationId,
                        String source, String channel, String mode, String outcome, byte[] payload) {
        this(type, requestId, messageId, correlationId, "", source, channel, mode, outcome, payload);
    }

    public ChannelFrame(byte type, String requestId, String messageId, String correlationId,
                        String idempotencyKey, String source, String channel, String mode, String outcome, byte[] payload) {
        this.type = type;
        this.requestId = value(requestId);
        this.messageId = value(messageId);
        this.correlationId = value(correlationId);
        this.idempotencyKey = value(idempotencyKey);
        this.source = value(source);
        this.channel = value(channel);
        this.mode = value(mode);
        this.outcome = value(outcome);
        this.payload = payload == null ? new byte[0] : payload.clone();
    }

    private static String value(String value) { return value == null ? "" : value; }
}

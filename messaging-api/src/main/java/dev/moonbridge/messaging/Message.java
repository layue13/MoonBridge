package dev.moonbridge.messaging;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable message envelope shared by the proxy and Java 8 backend clients. The UUID identifies a message
 * for tracing and request/reply correlation; it does not imply durable delivery, deduplication, or exactly-once
 * processing. Sending APIs assign the source from the authenticated host identity.
 */
public final class Message {
    public static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    public static final int MAX_CHANNEL_BYTES = 128;

    private final UUID id;
    private final MessageKind kind;
    private final String channel;
    private final Endpoint source;
    private final Endpoint target;
    private final UUID replyTo;
    private final byte[] payload;

    /** Creates a decoded message. Sending APIs replace the source with their authenticated host identity. */
    public Message(UUID id, MessageKind kind, String channel, Endpoint source, Endpoint target,
                   UUID replyTo, byte[] payload) {
        this.id = Objects.requireNonNull(id, "id");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.channel = validateChannel(channel);
        this.source = Objects.requireNonNull(source, "source");
        this.target = target;
        this.replyTo = replyTo;
        if (kind == MessageKind.REPLY && replyTo == null) {
            throw new IllegalArgumentException("replyTo is required for a reply");
        }
        if (kind != MessageKind.REPLY && replyTo != null) {
            throw new IllegalArgumentException("replyTo is only valid for a reply");
        }
        if (kind != MessageKind.EVENT && target == null) {
            throw new IllegalArgumentException("target is required for requests and replies");
        }
        this.payload = copyPayload(payload);
    }

    public static Message event(String channel, Endpoint source, Endpoint target, byte[] payload) {
        return new Message(UUID.randomUUID(), MessageKind.EVENT, channel, source, target, null, payload);
    }

    public static Message request(String channel, Endpoint source, Endpoint target, byte[] payload) {
        return new Message(UUID.randomUUID(), MessageKind.REQUEST, channel, source,
                Objects.requireNonNull(target, "target"), null, payload);
    }

    public static Message reply(Message request, Endpoint source, byte[] payload) {
        Objects.requireNonNull(request, "request");
        if (request.kind != MessageKind.REQUEST) {
            throw new IllegalArgumentException("request must have REQUEST kind");
        }
        return new Message(UUID.randomUUID(), MessageKind.REPLY, request.channel, source,
                request.source, request.id, payload);
    }

    public UUID id() { return id; }
    public MessageKind kind() { return kind; }
    public String channel() { return channel; }
    public Endpoint source() { return source; }
    public Endpoint target() { return target; }
    public UUID replyTo() { return replyTo; }

    /** Returns a defensive copy of the payload. */
    public byte[] payload() { return payload.clone(); }

    public static String validateChannel(String channel) {
        Objects.requireNonNull(channel, "channel");
        byte[] bytes = channel.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length == 0 || bytes.length > MAX_CHANNEL_BYTES || !channel.matches(
                "[a-z0-9][a-z0-9_.-]*:[a-z0-9][a-z0-9_.-]*")) {
            throw new IllegalArgumentException("channel must be namespace:name using lowercase ASCII letters, digits, '.', '_' or '-'");
        }
        return channel;
    }

    private static byte[] copyPayload(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("payload exceeds 64 KiB");
        }
        return payload.clone();
    }
}

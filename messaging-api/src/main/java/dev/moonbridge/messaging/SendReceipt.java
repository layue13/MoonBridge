package dev.moonbridge.messaging;

import java.util.Objects;
import java.util.UUID;

/** Message identity and target-side queue acceptance for a one-way send. */
public final class SendReceipt {
    private final UUID messageId;
    private final SendResult result;

    public SendReceipt(UUID messageId, SendResult result) {
        this.messageId = Objects.requireNonNull(messageId, "messageId");
        this.result = Objects.requireNonNull(result, "result");
    }

    public UUID messageId() { return messageId; }
    public SendResult result() { return result; }
}

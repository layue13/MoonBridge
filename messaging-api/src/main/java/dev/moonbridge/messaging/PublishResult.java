package dev.moonbridge.messaging;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Per-endpoint queue acceptance results for a single published event. */
public final class PublishResult {
    private final UUID messageId;
    private final Map<Endpoint, SendResult> results;

    public PublishResult(UUID messageId, Map<Endpoint, SendResult> results) {
        this.messageId = Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(results, "results");
        Map<Endpoint, SendResult> copy = new LinkedHashMap<Endpoint, SendResult>();
        for (Map.Entry<Endpoint, SendResult> entry : results.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), "endpoint"),
                    Objects.requireNonNull(entry.getValue(), "result"));
        }
        this.results = Collections.unmodifiableMap(copy);
    }

    public UUID messageId() { return messageId; }
    public Map<Endpoint, SendResult> results() { return results; }
}

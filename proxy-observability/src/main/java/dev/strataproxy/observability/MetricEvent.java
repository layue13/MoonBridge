package dev.strataproxy.observability;

import java.time.Instant;
import java.util.Map;

public record MetricEvent(
        String name,
        Map<String, String> attributes,
        double value,
        Instant timestamp) {
    public MetricEvent {
        attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }
}

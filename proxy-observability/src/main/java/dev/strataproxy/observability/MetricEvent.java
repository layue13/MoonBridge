package dev.strataproxy.observability;

import java.time.Instant;
import java.util.Map;

/**
 * Lightweight metric or diagnostic event.
 *
 * @param name event name
 * @param attributes dimensions associated with the event
 * @param value numeric value for counters, gauges, or samples
 * @param timestamp event timestamp
 */
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

package dev.strataproxy.observability;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe in-memory event sink used by tests and admin diagnostics.
 */
public final class InMemoryEventSink implements EventSink {
    private final CopyOnWriteArrayList<MetricEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void publish(MetricEvent event) {
        events.add(event);
    }

    /**
     * @return immutable snapshot of events currently retained by the sink
     */
    public List<MetricEvent> snapshot() {
        return List.copyOf(events);
    }
}

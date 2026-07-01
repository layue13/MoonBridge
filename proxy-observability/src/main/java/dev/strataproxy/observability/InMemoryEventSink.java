package dev.strataproxy.observability;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe in-memory event sink used by tests and admin diagnostics.
 */
public final class InMemoryEventSink implements EventSink {
    /**
     * Creates InMemoryEventSink.
     */
    public InMemoryEventSink() {
    }

    private final CopyOnWriteArrayList<MetricEvent> events = new CopyOnWriteArrayList<>();

    @Override
    /** Provides publish. */
    public void publish(MetricEvent event) {
        events.add(event);
    }

    /**
 * Documents this public API element.
 *
     * @return immutable snapshot of events currently retained by the sink
     */
    public List<MetricEvent> snapshot() {
        return List.copyOf(events);
    }
}

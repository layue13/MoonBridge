package dev.strataproxy.observability;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InMemoryEventSink implements EventSink {
    private final CopyOnWriteArrayList<MetricEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void publish(MetricEvent event) {
        events.add(event);
    }

    public List<MetricEvent> snapshot() {
        return List.copyOf(events);
    }
}

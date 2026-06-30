package dev.strataproxy.observability;

public interface EventSink {
    void publish(MetricEvent event);
}

package dev.strataproxy.observability;

/**
 * Destination for metric and diagnostic events.
 */
public interface EventSink {
    /**
     * Publishes an event to the sink.
     *
     * @param event metric event
     */
    void publish(MetricEvent event);
}

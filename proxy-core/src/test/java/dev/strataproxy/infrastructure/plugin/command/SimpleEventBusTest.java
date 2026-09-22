package dev.strataproxy.infrastructure.plugin.command;

import dev.strataproxy.plugin.event.ProxyEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SimpleEventBusTest {
    @Test
    void listenerFailureDoesNotStopLaterListeners() {
        var bus = new SimpleEventBus(LoggerFactory.getLogger("test"));
        var handled = new AtomicInteger();
        bus.subscribe(TestEvent.class, event -> {
            throw new IllegalStateException("boom");
        });
        bus.subscribe(TestEvent.class, event -> handled.incrementAndGet());

        bus.publish(new TestEvent());

        assertEquals(1, handled.get());
    }

    private record TestEvent() implements ProxyEvent {
    }
}

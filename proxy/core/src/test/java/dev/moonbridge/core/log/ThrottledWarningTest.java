package dev.moonbridge.core.log;

import dev.moonbridge.testing.LogCapture;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThrottledWarningTest {
    @Test
    void firstOccurrenceIsImmediateAndTheRestAreCountedPerInterval() {
        var clock = new AtomicLong();
        try (var log = new LogCapture("test.throttled")) {
            var warning = new ThrottledWarning(LoggerFactory.getLogger("test.throttled"), "refused {}",
                    1, TimeUnit.MINUTES, clock::get);
            warning.record();
            for (int i = 0; i < 3; i++) warning.record();
            assertEquals(List.of("WARN refused 1"), log.lines());
            clock.addAndGet(TimeUnit.MINUTES.toNanos(1));
            warning.record();
            assertEquals(List.of("WARN refused 1", "WARN refused 4"), log.lines());
        }
    }
}

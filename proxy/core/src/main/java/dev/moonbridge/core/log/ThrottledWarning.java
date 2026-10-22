package dev.moonbridge.core.log;

import org.slf4j.Logger;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * A warning for something that can happen many times a second, such as refused connections under load. The first
 * occurrence is logged at once; later ones are counted and reported in one line by the next occurrence after the
 * interval, so a flood costs one line per interval instead of one per event.
 */
public final class ThrottledWarning {
    private final Logger logger;
    private final String message;
    private final long intervalNanos;
    private final LongSupplier nanoTime;
    private final AtomicLong suppressed = new AtomicLong();
    private final AtomicLong nextReportNanos;

    /** {@code message} has one placeholder: the number of occurrences the line covers. */
    public ThrottledWarning(Logger logger, String message, long interval, TimeUnit unit) {
        this(logger, message, interval, unit, System::nanoTime);
    }

    ThrottledWarning(Logger logger, String message, long interval, TimeUnit unit, LongSupplier nanoTime) {
        this.logger = logger;
        this.message = message;
        this.intervalNanos = unit.toNanos(interval);
        this.nanoTime = nanoTime;
        this.nextReportNanos = new AtomicLong(nanoTime.getAsLong());
    }

    public void record() {
        suppressed.incrementAndGet();
        long now = nanoTime.getAsLong();
        long due = nextReportNanos.get();
        if (now - due < 0 || !nextReportNanos.compareAndSet(due, now + intervalNanos)) return;
        // Only the thread that moved the deadline reports, so each counted occurrence is logged exactly once.
        long count = suppressed.getAndSet(0);
        if (count > 0) logger.warn(message, count);
    }
}

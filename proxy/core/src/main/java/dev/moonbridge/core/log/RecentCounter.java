package dev.moonbridge.core.log;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.LongSupplier;

/**
 * Counts events over the last ten minutes in one-minute buckets, for the admin console's activity line. Recording is
 * two atomic operations and never blocks, so it can sit on the network threads.
 */
public final class RecentCounter {
    static final int MINUTES = 10;
    private static final long MINUTE_NANOS = TimeUnit.MINUTES.toNanos(1);

    private final LongSupplier nanoTime;
    private final long origin;
    /** Bucket i holds the count for the minute number stored in minutes[i]. */
    private final AtomicLongArray counts = new AtomicLongArray(MINUTES);
    private final AtomicLongArray minutes = new AtomicLongArray(MINUTES);

    public RecentCounter() { this(System::nanoTime); }

    RecentCounter(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
        this.origin = nanoTime.getAsLong();
        for (int i = 0; i < MINUTES; i++) minutes.set(i, -1);
    }

    public void increment() {
        long minute = minute();
        int slot = (int) (minute % MINUTES);
        long owner = minutes.get(slot);
        // The first event of a new minute reclaims the slot; a racing increment of the old minute may be lost or
        // land in the new one, which is acceptable for an operator's approximate count.
        if (owner != minute && minutes.compareAndSet(slot, owner, minute)) counts.set(slot, 0);
        counts.incrementAndGet(slot);
    }

    /** Events in the current minute and the nine before it. */
    public long lastTenMinutes() {
        long now = minute();
        long sum = 0;
        for (int i = 0; i < MINUTES; i++) {
            long owner = minutes.get(i);
            if (owner >= 0 && now - owner < MINUTES) sum += counts.get(i);
        }
        return sum;
    }

    private long minute() { return (nanoTime.getAsLong() - origin) / MINUTE_NANOS; }
}

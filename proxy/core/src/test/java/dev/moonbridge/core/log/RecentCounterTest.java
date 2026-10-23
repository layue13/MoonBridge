package dev.moonbridge.core.log;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecentCounterTest {
    @Test
    void countsTheLastTenMinutesAndForgetsOlderOnes() {
        var clock = new AtomicLong();
        var counter = new RecentCounter(clock::get);
        counter.increment();
        clock.addAndGet(TimeUnit.MINUTES.toNanos(5));
        counter.increment();
        counter.increment();
        assertEquals(3, counter.lastTenMinutes());
        clock.addAndGet(TimeUnit.MINUTES.toNanos(6));
        assertEquals(2, counter.lastTenMinutes(), "the first minute's event is now older than ten minutes");
        clock.addAndGet(TimeUnit.MINUTES.toNanos(10));
        counter.increment();
        assertEquals(1, counter.lastTenMinutes(), "a reused bucket starts from zero");
    }
}

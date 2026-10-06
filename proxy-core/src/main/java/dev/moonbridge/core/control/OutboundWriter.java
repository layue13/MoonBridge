package dev.moonbridge.core.control;


import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import static dev.moonbridge.core.control.ControlFailures.*;
import static dev.moonbridge.core.control.ControlMessages.*;
import static dev.moonbridge.core.control.ControlProtocol.*;

/** Bounded asynchronous frame writes: per-connection and global queue limits plus a flush deadline. */
final class OutboundWriter {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final long MAX_GLOBAL_QUEUED_BYTES = 64L * 1_048_576;
    private static final int MAX_WRITE_TASKS = 512;

    private final ScheduledExecutorService timer;
    private final AtomicLong globalQueuedBytes = new AtomicLong();
    private final Semaphore writeTasks = new Semaphore(MAX_WRITE_TASKS);

    OutboundWriter(ScheduledExecutorService timer) { this.timer = timer; }

    void writeAsync(Connection connection, byte[] frame) {
        writeAsync(connection, frame, deadlineAfterMillis(REQUEST_TIMEOUT.toMillis()));
    }

    void writeAsync(Connection connection, byte[] frame, long deadline) {
        if (!reserveWrite(connection, frame.length)) {
            connection.close();
            return;
        }
        AtomicBoolean writeStarted = new AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<ScheduledFuture<?>> flushTimeout =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread.ofVirtual().name("moonbridge-control-write").start(() -> {
            try {
                connection.writeFrame(() -> {
                    if (deadline - System.nanoTime() <= 0) return null;
                    writeStarted.set(true);
                    if (deadline - System.nanoTime() <= 0) {
                        writeStarted.set(false);
                        return null;
                    }
                    try {
                        flushTimeout.set(timer.schedule(connection::close,
                                REQUEST_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS));
                    } catch (RuntimeException timerClosed) {
                        writeStarted.set(false);
                        throw new IOException("backend control service closed before response write", timerClosed);
                    }
                    return frame;
                }, () -> {
                    writeStarted.set(false);
                    ScheduledFuture<?> timeout = flushTimeout.getAndSet(null);
                    if (timeout != null) timeout.cancel(false);
                });
            }
            catch (IOException ignored) { connection.close(); }
            finally {
                ScheduledFuture<?> timeout = flushTimeout.getAndSet(null);
                if (timeout != null) timeout.cancel(false);
                releaseWrite(connection, frame.length);
            }
        });
    }

    boolean reserveWrite(Connection connection, int size) {
        if (!writeTasks.tryAcquire()) return false;
        if (!connection.reserve(size)) {
            writeTasks.release();
            return false;
        }
        for (;;) {
            long used = globalQueuedBytes.get();
            if (used + size > MAX_GLOBAL_QUEUED_BYTES) {
                connection.release(size);
                writeTasks.release();
                return false;
            }
            if (globalQueuedBytes.compareAndSet(used, used + size)) return true;
        }
    }

    void releaseWrite(Connection connection, int size) {
        connection.release(size);
        globalQueuedBytes.addAndGet(-size);
        writeTasks.release();
    }
}

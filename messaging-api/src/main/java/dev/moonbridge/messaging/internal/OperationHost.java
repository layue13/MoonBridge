package dev.moonbridge.messaging.internal;

import java.util.concurrent.ScheduledExecutorService;

/** What an {@link Operation} needs from the messaging host that admitted it. */
interface OperationHost {
    ScheduledExecutorService scheduler();

    /** Runs {@code completion} on the completion executor, releasing the admission slot first. */
    void complete(Operation<?> operation, Runnable completion);

    /** Releases the admission slot of an operation that was cancelled before completing. */
    void finished(Operation<?> operation);
}

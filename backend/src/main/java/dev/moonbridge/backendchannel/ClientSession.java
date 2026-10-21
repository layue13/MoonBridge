package dev.moonbridge.backendchannel;

import dev.moonbridge.messaging.Message;
import dev.moonbridge.messaging.MessagingException;
import dev.moonbridge.messaging.protocol.MessageCodec;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static dev.moonbridge.backendchannel.Failures.*;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;

final class ClientSession {
    final Socket socket;
    final Object writeLock = new Object();
    final LinkedBlockingDeque<WriteTask> writeQueue = new LinkedBlockingDeque<WriteTask>();
    final AtomicInteger queuedCount = new AtomicInteger();
    final AtomicLong queuedBytes = new AtomicLong();
    final AtomicLong nextOperationId = new AtomicLong(1L);
    final Semaphore requestSlots = new Semaphore(MAX_PENDING_REQUESTS);
    final Map<Long, PendingOperation> pending = new ConcurrentHashMap<Long, PendingOperation>();
    volatile DataInputStream in;
    volatile DataOutputStream out;
    volatile ScheduledFuture<?> heartbeatTask;
    volatile ScheduledFuture<?> livenessTask;
    volatile long lastPongNanos;
    volatile Thread writerThread;
    private final AtomicBoolean ended = new AtomicBoolean();

    static final int MAX_PENDING_REQUESTS = BackendChannelClient.MAX_PENDING_REQUESTS;
    static final int MAX_WRITER_QUEUE = BackendChannelClient.MAX_WRITER_QUEUE;
    static final long MAX_WRITER_QUEUE_BYTES = BackendChannelClient.MAX_WRITER_QUEUE_BYTES;
    static final long WRITE_COMPLETION_TIMEOUT_MILLIS = 5000L;

    final ScheduledExecutorService timer;
    private final String instanceId;

    ClientSession(Socket socket, ScheduledExecutorService timer, String instanceId) {
        this.socket = socket;
        this.timer = timer;
        this.instanceId = instanceId;
    }

    static boolean isExpired(long deadlineNanos) {
        return deadlineNanos - System.nanoTime() <= 0;
    }

    void initialize() throws IOException {
        this.in = new DataInputStream(socket.getInputStream());
        this.out = new DataOutputStream(socket.getOutputStream());
    }

    synchronized void writeDirect(byte[] frame) throws IOException {
        if (isClosed()) throw new IOException("connection closed");
        FrameCodec.write(out, frame);
    }

    void startWriter() {
        Thread writer = new Thread(new Runnable() {
            @Override public void run() { writerLoop(); }
        }, "backend-channel-writer-" + instanceId);
        writer.setDaemon(true);
        writerThread = writer;
        writer.start();
    }

    boolean enqueueControlQuietly(byte[] frame, long deadlineNanos) {
        return enqueue(frame, deadlineNanos, null, new CompletableFuture<Void>());
    }

    boolean enqueue(byte[] frame, long deadlineNanos, PendingOperation operation, CompletableFuture<Void> written) {
        return enqueue(frame, deadlineNanos, operation, written, null, 0L, 0L);
    }

    boolean enqueue(byte[] frame, long deadlineNanos, PendingOperation operation, CompletableFuture<Void> written,
                    Message message, long operationId, long originalTimeoutMillis) {
        if (frame == null || frame.length < 1 || frame.length > Wire.MAX_FRAME_BYTES || isClosed()) return false;
        WriteTask task = new WriteTask(frame, deadlineNanos, operation, written,
                message, operationId, originalTimeoutMillis);
        synchronized (writeLock) {
            if (isClosed() || (operation != null && operation.isDone()) || isExpired(deadlineNanos)) return false;
            if (queuedCount.get() >= MAX_WRITER_QUEUE || queuedBytes.get() + frame.length > MAX_WRITER_QUEUE_BYTES) return false;
            if (!writeQueue.offerLast(task)) return false;
            queuedCount.incrementAndGet();
            queuedBytes.addAndGet(frame.length);
            task.accounted = true;
            if (operation != null) operation.writeTask = task;
            try {
                final long generation = ++task.expiryGeneration;
                task.expiryTask = timer.schedule(() -> expireWriteTask(task, generation),
                        Math.max(0L, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (RejectedExecutionException stopped) {
                writeQueue.remove(task);
                releaseLocked(task);
                return false;
            }
            writeLock.notifyAll();
        }
        return true;
    }

    private void expireWriteTask(WriteTask task, long generation) {
        boolean closeForBlockedWrite = false;
        boolean removed = false;
        synchronized (writeLock) {
            if (!task.accounted || task.expiryGeneration != generation || task.writeCompleted) return;
            if (writeQueue.remove(task)) {
                releaseLocked(task);
                removed = true;
            } else if (task.writeInProgress) {
                closeForBlockedWrite = true;
            }
        }
        MessagingException timeout = new MessagingException(MessagingException.Code.TIMED_OUT,
                "backend frame expired before socket write completed");
        if (task.pending != null && (removed || closeForBlockedWrite)) task.pending.fail(timeout);
        if (task.written != null && removed) task.written.completeExceptionally(timeout);
        if (closeForBlockedWrite) close(timeout);
    }

    private void writerLoop() {
        try {
            while (true) {
                WriteTask task;
                boolean canWrite;
                synchronized (writeLock) {
                    while (writeQueue.isEmpty() && !isClosed()) writeLock.wait();
                    if (isClosed()) break;
                    task = writeQueue.pollFirst();
                    if (task == null) continue;
                    task.started = true;
                    canWrite = !isExpired(task.deadlineNanos)
                            && (task.pending == null || !task.pending.isDone());
                }
                if (!canWrite) {
                    if (task.pending != null && !task.pending.isDone()) {
                        task.pending.fail(isExpired(task.deadlineNanos)
                                ? new MessagingException(MessagingException.Code.TIMED_OUT, "backend message expired before socket write")
                                : notConnected());
                    }
                    if (task.written != null) task.written.completeExceptionally(new IOException("control frame expired or connection closed"));
                    release(task);
                    continue;
                }
                try {
                    byte[] frame = task.frame;
                    if (task.message != null) {
                        long remaining = TimeUnit.NANOSECONDS.toMillis(task.deadlineNanos - System.nanoTime());
                        if (remaining <= 0) {
                            if (task.pending != null) task.pending.fail(new MessagingException(
                                    MessagingException.Code.TIMED_OUT, "backend message expired before socket write"));
                            continue;
                        }
                        remaining = Math.min(task.originalTimeoutMillis, Math.min(MessageCodec.MAX_TIMEOUT_MILLIS, remaining));
                        frame = MessageCodec.message(task.operationId, remaining, task.message);
                    }
                    boolean startWrite;
                    boolean expiredBeforeWrite;
                    synchronized (writeLock) {
                        expiredBeforeWrite = isExpired(task.deadlineNanos);
                        startWrite = !isClosed() && !expiredBeforeWrite
                                && (task.pending == null || !task.pending.isDone());
                        if (startWrite) {
                            task.writeInProgress = true;
                            ScheduledFuture<?> queueExpiry = task.expiryTask;
                            if (queueExpiry != null) queueExpiry.cancel(false);
                            try {
                                final long generation = ++task.expiryGeneration;
                                task.expiryTask = timer.schedule(() -> expireWriteTask(task, generation),
                                        WRITE_COMPLETION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                            } catch (RejectedExecutionException stopped) {
                                task.writeInProgress = false;
                                startWrite = false;
                            }
                        }
                    }
                    if (!startWrite) {
                        if (task.pending != null && !task.pending.isDone()) task.pending.fail(expiredBeforeWrite
                                ? new MessagingException(MessagingException.Code.TIMED_OUT,
                                        "backend message expired before socket write") : notConnected());
                        if (task.written != null) task.written.completeExceptionally(
                                new IOException("control frame expired or connection closed"));
                        continue;
                    }
                    FrameCodec.write(out, frame);
                    task.writeCompleted = true;
                    task.writeInProgress = false;
                    if (task.written != null) task.written.complete(null);
                } catch (IOException failure) {
                    task.writeInProgress = false;
                    if (task.written != null) task.written.completeExceptionally(failure);
                    if (task.pending != null) task.pending.fail(notConnected());
                    close(failure);
                } finally {
                    release(task);
                }
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } finally {
            WriteTask task;
            while ((task = writeQueue.pollFirst()) != null) {
                if (task.written != null) task.written.completeExceptionally(new IOException("connection closed"));
                release(task);
            }
        }
    }

    private void release(WriteTask task) {
        synchronized (writeLock) {
            releaseLocked(task);
        }
    }

    private void releaseLocked(WriteTask task) {
        if (!task.accounted) return;
        task.accounted = false;
        task.expiryGeneration++;
        queuedCount.decrementAndGet();
        queuedBytes.addAndGet(-task.frame.length);
        ScheduledFuture<?> expiry = task.expiryTask;
        if (expiry != null) expiry.cancel(false);
        if (task.pending != null && task.pending.writeTask == task) task.pending.writeTask = null;
        writeLock.notifyAll();
    }

    void remove(WriteTask task) {
        synchronized (writeLock) {
            if (writeQueue.remove(task)) {
                releaseLocked(task);
            }
        }
    }

    void handleResponse(MessageCodec.Response response) {
        PendingOperation operation = pending.get(response.operationId);
        if (operation != null) operation.accept(response);
    }

    void awaitWriter(CompletableFuture<Void> written, long millis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        try { written.get(Math.max(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
        catch (Exception doneOrExpired) { /* Close remains bounded even when the peer is slow. */ }
    }

    boolean isClosed() { return ended.get() || socket.isClosed(); }

    void close(Throwable cause) {
        if (!ended.compareAndSet(false, true)) return;
        ScheduledFuture<?> heart = heartbeatTask;
        if (heart != null) heart.cancel(false);
        ScheduledFuture<?> live = livenessTask;
        if (live != null) live.cancel(false);
        try { socket.close(); } catch (IOException ignored) { }
        Thread writer = writerThread;
        if (writer != null) writer.interrupt();
        Throwable error;
        if (cause instanceof MessagingException) error = cause;
        else error = new MessagingException(MessagingException.Code.NOT_CONNECTED,
                "backend channel connection closed", cause);
        for (PendingOperation operation : pending.values()) operation.fail(error);
    }
}

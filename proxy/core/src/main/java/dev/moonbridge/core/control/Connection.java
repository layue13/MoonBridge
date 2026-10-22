package dev.moonbridge.core.control;

import dev.moonbridge.messaging.MessagingException;
import dev.moonbridge.messaging.protocol.MessageCodec;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static dev.moonbridge.core.control.ControlFailures.*;
import static dev.moonbridge.core.control.ControlMessages.*;
import static dev.moonbridge.core.control.ControlProtocol.*;

/** One authenticated backend socket: framed writes, queue accounting, rate limit and in-flight operations. */
final class Connection {
    static final int MAX_REQUESTS = 128;
    static final long MAX_QUEUED_BYTES = 1_048_576;

    interface Writer { void write(DataOutputStream output) throws IOException; }
    interface FrameSupplier { byte[] get() throws IOException; }

    static final class PendingOperation {
        final CompletableFuture<MessageCodec.Response> result;
        final MessageCodec.Response.Type expected;
        PendingOperation(CompletableFuture<MessageCodec.Response> result,
                                 MessageCodec.Response.Type expected) {
            this.result = result;
            this.expected = expected;
        }
    }

    final Socket socket;
    final DataInputStream input;
    final DataOutputStream output;
    final Object writeLock = new Object();
    final AtomicBoolean live = new AtomicBoolean(true);
    final AtomicLong queuedBytes = new AtomicLong();
    final AtomicInteger queuedMessages = new AtomicInteger();
    final AtomicLong nextRequest = new AtomicLong(1);
    final ConcurrentHashMap<Long, PendingOperation> pending = new ConcurrentHashMap<>();
    final AtomicInteger inboundRequests = new AtomicInteger();
    final Semaphore requestSlots = new Semaphore(MAX_REQUESTS);
    String instanceId, name;
    java.util.Set<String> allowedNamespaces = java.util.Set.of();
    java.util.Set<String> allowedReceiveNamespaces = java.util.Set.of();
    long epoch;
    long rateWindowNanos = System.nanoTime();
    int messagesInWindow;
    Connection(Socket socket) {
        this.socket = socket;
        try {
            this.input = new DataInputStream(socket.getInputStream());
            this.output = new DataOutputStream(socket.getOutputStream());
        } catch (IOException failure) { throw new IllegalStateException(failure); }
    }
    boolean reserve(int size) {
        if (queuedMessages.incrementAndGet() > MAX_REQUESTS) {
            queuedMessages.decrementAndGet();
            return false;
        }
        for (;;) {
            long used = queuedBytes.get();
            if (used + size > MAX_QUEUED_BYTES) {
                queuedMessages.decrementAndGet();
                return false;
            }
            if (queuedBytes.compareAndSet(used, used + size)) return true;
        }
    }
    void release(int size) {
        queuedBytes.addAndGet(-size);
        queuedMessages.decrementAndGet();
    }
    boolean allowMessage() {
        long now = System.nanoTime();
        if (now - rateWindowNanos >= TimeUnit.SECONDS.toNanos(1)) {
            rateWindowNanos = now;
            messagesInWindow = 0;
        }
        return ++messagesInWindow <= 200;
    }
    void write(int type, Writer writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream frame = new DataOutputStream(bytes);
        frame.writeByte(type);
        writer.write(frame);
        if (bytes.size() > MAX_FRAME) throw new IOException("control frame too large");
        synchronized (writeLock) {
            if (!live.get()) throw new SocketException("backend control disconnected");
            output.writeInt(bytes.size());
            bytes.writeTo(output);
            output.flush();
        }
    }
    boolean writeFrame(FrameSupplier frameSupplier, Runnable afterWrite) throws IOException {
        synchronized (writeLock) {
            if (!live.get()) throw new SocketException("backend control disconnected");
            byte[] frame = frameSupplier.get();
            if (frame == null) return false;
            if (frame.length < 1 || frame.length > MAX_FRAME)
                throw new IOException("control frame length out of bounds");
            output.writeInt(frame.length);
            output.write(frame);
            output.flush();
            afterWrite.run();
            return true;
        }
    }
    void close() {
        if (!live.compareAndSet(true, false)) return;
        try { socket.close(); } catch (IOException ignored) { }
        for (PendingOperation operation : pending.values())
            operation.result.completeExceptionally(new MessagingException(MessagingException.Code.NOT_CONNECTED,
                    "backend control disconnected"));
        pending.clear();
    }
}

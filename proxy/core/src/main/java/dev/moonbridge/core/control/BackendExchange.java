package dev.moonbridge.core.control;

import dev.moonbridge.messaging.Endpoint;
import dev.moonbridge.messaging.Message;
import dev.moonbridge.messaging.MessagingException;
import dev.moonbridge.messaging.SendResult;
import dev.moonbridge.messaging.protocol.MessageCodec;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.RejectedExecutionException;
import static dev.moonbridge.core.control.ControlFailures.*;
import static dev.moonbridge.core.control.ControlMessages.*;
import static dev.moonbridge.core.control.ControlProtocol.*;

/** Proxy-initiated operations toward a backend: write one message and await its matching response. */
final class BackendExchange {
    private final ScheduledExecutorService timer;
    private final OutboundWriter writer;
    private final ThreadPoolExecutor routingWorkers;

    BackendExchange(ScheduledExecutorService timer, OutboundWriter writer, ThreadPoolExecutor routingWorkers) {
        this.timer = timer;
        this.writer = writer;
        this.routingWorkers = routingWorkers;
    }

    CompletionStage<SendResult> sendTo(Connection connection, Message message, Duration timeout) {
        return mapCancellable(exchange(connection, message, timeout, MessageCodec.Response.Type.SEND),
                response -> response.sendResult);
    }

    CompletionStage<Message> requestFrom(Connection connection, Message message, Duration timeout) {
        return mapCancellable(exchange(connection, message, timeout, MessageCodec.Response.Type.REPLY), response -> {
            Message reply = response.message;
            if (reply == null || !message.id().equals(reply.replyTo())
                    || !message.channel().equals(reply.channel())
                    || !Endpoint.backend(connection.name).equals(reply.source())
                    || !message.source().equals(reply.target())) {
                throw new MessagingException(MessagingException.Code.PROTOCOL_ERROR,
                        "backend returned a reply for a different request or identity");
            }
            return reply;
        });
    }

    private CompletionStage<MessageCodec.Response> exchange(Connection connection, Message message, Duration timeout,
                                                              MessageCodec.Response.Type expected) {
        if (timeout == null || timeout.isNegative() || timeout.isZero())
            return failed(new MessagingException(MessagingException.Code.TIMED_OUT, "message operation timed out"));
        long timeoutMillis;
        try { timeoutMillis = Math.max(1, Math.min(MessageCodec.MAX_TIMEOUT_MILLIS, timeout.toMillis())); }
        catch (ArithmeticException overflow) { timeoutMillis = MessageCodec.MAX_TIMEOUT_MILLIS; }
        if (!connection.requestSlots.tryAcquire()) return failed(new MessagingException(
                MessagingException.Code.BACKPRESSURED, "backend has too many in-flight operations"));
        long operationId = connection.nextRequest.getAndIncrement();
        if (operationId <= 0) {
            connection.requestSlots.release();
            return failed(new MessagingException(MessagingException.Code.REJECTED,
                    "backend operation ID space exhausted"));
        }
        final byte[] frame;
        try { frame = MessageCodec.message(operationId, timeoutMillis, message); }
        catch (IOException | RuntimeException invalid) {
            connection.requestSlots.release();
            return failed(new MessagingException(MessagingException.Code.REJECTED,
                    "message cannot be encoded", invalid));
        }
        final long deadline = deadlineAfterMillis(timeoutMillis);
        if (!writer.reserveWrite(connection, frame.length)) {
            connection.requestSlots.release();
            return failed(new MessagingException(MessagingException.Code.BACKPRESSURED,
                    "backend outbound queue is full"));
        }
        CompletableFuture<MessageCodec.Response> result = new CompletableFuture<>();
        Connection.PendingOperation pending = new Connection.PendingOperation(result, expected);
        if (connection.pending.putIfAbsent(operationId, pending) != null) {
            writer.releaseWrite(connection, frame.length);
            connection.requestSlots.release();
            return failed(new MessagingException(MessagingException.Code.REJECTED,
                    "backend operation ID collision"));
        }
        AtomicBoolean writeStarted = new AtomicBoolean();
        ScheduledFuture<?> timeoutTask;
        try {
            timeoutTask = timer.schedule(() -> {
                        if (result.isCancelled()) {
                            if (writeStarted.get()) connection.close();
                        } else if (result.completeExceptionally(new MessagingException(
                                MessagingException.Code.TIMED_OUT, "backend message operation timed out"))
                                && writeStarted.get()) connection.close();
                    },
                    timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException closedTimer) {
            result.completeExceptionally(new MessagingException(MessagingException.Code.CLOSED,
                    "backend control service is closed", closedTimer));
            timeoutTask = null;
        }
        final ScheduledFuture<?> operationTimeout = timeoutTask;
        result.whenComplete((value, failure) -> {
            connection.pending.remove(operationId, pending);
            if (operationTimeout != null && (!result.isCancelled() || !writeStarted.get()))
                operationTimeout.cancel(false);
            connection.requestSlots.release();
        });
        Thread.ofVirtual().name("moonbridge-control-write").start(() -> {
            try {
                boolean written = connection.writeFrame(() -> {
                    long remainingNanos = deadline - System.nanoTime();
                    if (result.isDone() || remainingNanos <= 0) return null;
                    long remainingMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
                    byte[] currentFrame = MessageCodec.message(operationId, remainingMillis, message);
                    writeStarted.set(true);
                    if (result.isDone() || deadline - System.nanoTime() <= 0) {
                        writeStarted.set(false);
                        return null;
                    }
                    return currentFrame;
                }, () -> writeStarted.set(false));
                if (!written && !result.isDone() && deadline - System.nanoTime() <= 0) {
                    result.completeExceptionally(new MessagingException(MessagingException.Code.TIMED_OUT,
                            "backend message operation timed out before write"));
                }
            } catch (IOException failure) {
                connection.close();
                result.completeExceptionally(new MessagingException(MessagingException.Code.NOT_CONNECTED,
                        "backend connection was lost while writing", failure));
            } finally { writer.releaseWrite(connection, frame.length); }
        });
        return result;
    }

    void receiveResponse(Connection connection, byte[] frame) throws IOException {
        MessageCodec.Response response = MessageCodec.decodeResponse(frame);
        Connection.PendingOperation pending = connection.pending.get(response.operationId);
        if (pending == null) return; // A timed-out operation may receive one late receipt.
        try {
            routingWorkers.execute(() -> {
                if (response.type == MessageCodec.Response.Type.ERROR) {
                    pending.result.completeExceptionally(new MessagingException(response.errorCode,
                            response.detail == null || response.detail.isEmpty()
                                    ? "remote message operation failed" : response.detail));
                } else if (response.type != pending.expected) {
                    pending.result.completeExceptionally(new MessagingException(MessagingException.Code.PROTOCOL_ERROR,
                            "backend returned the wrong response type"));
                    connection.close();
                } else {
                    pending.result.complete(response);
                }
            });
        } catch (RejectedExecutionException overloaded) {
            connection.close();
            throw new IOException("backend response dispatch is overloaded", overloaded);
        }
    }
}

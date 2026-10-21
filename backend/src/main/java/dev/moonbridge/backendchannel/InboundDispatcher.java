package dev.moonbridge.backendchannel;

import dev.moonbridge.messaging.Endpoint;
import dev.moonbridge.messaging.Message;
import dev.moonbridge.messaging.MessageKind;
import dev.moonbridge.messaging.MessagingException;
import dev.moonbridge.messaging.SendResult;
import dev.moonbridge.messaging.internal.LocalMessaging;
import dev.moonbridge.messaging.protocol.MessageCodec;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import static dev.moonbridge.backendchannel.Failures.*;
import static dev.moonbridge.backendchannel.ClientSession.isExpired;

/** Handles frames the proxy sends to this backend: heartbeats, events, requests and operation responses. */
final class InboundDispatcher {
    private final Endpoint self;
    private final LocalMessaging messaging;
    private final Supplier<ClientSession> current;

    InboundDispatcher(String backendName, LocalMessaging messaging, Supplier<ClientSession> current) {
        this.self = Endpoint.backend(backendName);
        this.messaging = messaging;
        this.current = current;
    }

    void handleFrame(final ClientSession active, byte[] frame) throws IOException {
        if (frame.length == 0) throw new IOException("empty frame");
        int type = frame[0] & 0xff;
        if (type == Wire.PONG) {
            Wire.validateEmpty(frame, Wire.PONG);
            active.lastPongNanos = System.nanoTime();
            return;
        }
        if (type == MessageCodec.MESSAGE) {
            handleMessage(active, MessageCodec.decodeMessage(frame));
            return;
        }
        if (type == MessageCodec.RESPONSE) {
            active.handleResponse(MessageCodec.decodeResponse(frame));
            return;
        }
        if (type == Wire.GOODBYE) {
            Wire.validateEmpty(frame, Wire.GOODBYE);
            throw new IOException("proxy closed channel");
        }
        throw new IOException("unexpected proxy frame type: " + type);
    }

    private void handleMessage(final ClientSession active, MessageCodec.IncomingMessage incoming) throws IOException {
        if (active != current.get()) return;
        final long operationId = incoming.operationId;
        final long timeoutMillis = incoming.timeoutMillis;
        long now = System.nanoTime();
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        final long deadlineNanos = Long.MAX_VALUE - now < timeoutNanos ? Long.MAX_VALUE : now + timeoutNanos;
        final Message message = incoming.message;
        if (message.kind() == MessageKind.EVENT) {
            if (!self.equals(message.target())) {
                sendError(active, operationId, MessagingException.Code.REJECTED, "event target does not match this backend", deadlineNanos);
                return;
            }
            if (isExpired(deadlineNanos)) return;
            SendResult result = messaging.receiveEvent(message);
            enqueueResponse(active, MessageCodec.sendResult(operationId, result), deadlineNanos);
            return;
        }
        if (message.kind() == MessageKind.REQUEST) {
            if (!self.equals(message.target())) {
                sendError(active, operationId, MessagingException.Code.REJECTED, "request target does not match this backend", deadlineNanos);
                return;
            }
            if (isExpired(deadlineNanos)) return;
            long remainingMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
            messaging.receiveRequest(message, Duration.ofMillis(remainingMillis)).whenComplete((reply, failure) -> {
                if (active != current.get() || active.isClosed()) return;
                if (failure != null) {
                    Throwable cause = unwrap(failure);
                    MessagingException.Code code = cause instanceof MessagingException
                            ? ((MessagingException) cause).code() : MessagingException.Code.HANDLER_FAILED;
                    sendError(active, operationId, code, safeDetail(cause), deadlineNanos);
                } else {
                    try { enqueueResponse(active, MessageCodec.reply(operationId, reply), deadlineNanos); }
                    catch (IOException invalid) { active.close(invalid); }
                }
            });
            return;
        }
        sendError(active, operationId, MessagingException.Code.REJECTED, "reply messages are not accepted as requests", deadlineNanos);
    }


    private void sendError(ClientSession active, long operationId, MessagingException.Code code, String detail,
                           long deadlineNanos) {
        try { enqueueResponse(active, MessageCodec.error(operationId, code, detail), deadlineNanos); }
        catch (IOException failure) { active.close(failure); }
    }

    private void enqueueResponse(ClientSession active, byte[] frame, long deadlineNanos) {
        if (isExpired(deadlineNanos)) return;
        if (!active.enqueue(frame, deadlineNanos, null, null)) {
            synchronized (active.writeLock) {
                if (active == current.get() && !active.isClosed() && !isExpired(deadlineNanos)) {
                    active.close(new IOException("could not queue messaging response"));
                }
            }
        }
    }

    private static boolean isExpired(long deadlineNanos) {
        return deadlineNanos - System.nanoTime() <= 0;
    }
}

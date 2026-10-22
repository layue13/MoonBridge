package dev.moonbridge.core.control;

import dev.moonbridge.messaging.Endpoint;
import dev.moonbridge.messaging.Message;
import dev.moonbridge.messaging.MessageKind;
import dev.moonbridge.messaging.MessagingException;
import dev.moonbridge.messaging.PublishResult;
import dev.moonbridge.messaging.SendResult;
import dev.moonbridge.messaging.internal.LocalMessaging;
import dev.moonbridge.messaging.protocol.MessageCodec;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import static dev.moonbridge.core.control.ControlFailures.*;
import static dev.moonbridge.core.control.ControlMessages.*;
import static dev.moonbridge.core.control.ControlProtocol.*;

/** Routes messages a backend sends: to the proxy's local messaging, to another backend, or to all of them. */
final class InboundRouter {
    private final LocalMessaging messaging;
    private final LeaseRegistry leases;
    private final BackendExchange exchange;
    private final OutboundWriter writer;

    InboundRouter(LocalMessaging messaging, LeaseRegistry leases, BackendExchange exchange, OutboundWriter writer) {
        this.messaging = messaging;
        this.leases = leases;
        this.exchange = exchange;
        this.writer = writer;
    }

    void receiveMessage(Connection connection, byte[] frame) throws IOException {
        MessageCodec.IncomingMessage incoming = MessageCodec.decodeMessage(frame);
        if (!connection.allowMessage()) throw new IOException("backend message rate exceeded");
        Message received = incoming.message;
        if (received.kind() == MessageKind.REPLY) throw new IOException("backend cannot send an unsolicited reply message");
        long deadline = deadlineAfterMillis(incoming.timeoutMillis);
        String namespace = namespace(received.channel());
        if (!connection.allowedNamespaces.contains(namespace)) {
            if (received.kind() == MessageKind.EVENT && received.target() != null) {
                writer.writeAsync(connection, MessageCodec.sendResult(incoming.operationId, SendResult.REJECTED), deadline);
            } else {
                writer.writeAsync(connection, MessageCodec.error(incoming.operationId, MessagingException.Code.REJECTED,
                        "backend message namespace rejected"), deadline);
            }
            return;
        }
        Message authenticated = new Message(received.id(), received.kind(), received.channel(),
                Endpoint.backend(connection.name), received.target(), received.replyTo(), received.payload());
        if (connection.inboundRequests.incrementAndGet() > Connection.MAX_REQUESTS) {
            connection.inboundRequests.decrementAndGet();
            writer.writeAsync(connection, MessageCodec.error(incoming.operationId,
                    MessagingException.Code.BACKPRESSURED, "inbound operation limit reached"), deadline);
            return;
        }
        switch (authenticated.kind()) {
            case EVENT:
                if (authenticated.target() == null) {
                    routePublish(connection, incoming.operationId, authenticated, deadline);
                } else {
                    routeSend(connection, incoming.operationId, authenticated, deadline);
                }
                break;
            case REQUEST:
                routeRequest(connection, incoming.operationId, authenticated, deadline);
                break;
            default:
                connection.inboundRequests.decrementAndGet();
                throw new IOException("unsupported backend message kind");
        }
    }

    private void routeSend(Connection origin, long operationId, Message message, long deadline) {
        CompletionStage<SendResult> delivery;
        Endpoint target = message.target();
        if (target.isProxy()) {
            delivery = CompletableFuture.completedFuture(messaging.receiveEvent(message));
        } else {
            Connection destination = leases.current(target.backendName());
            if (destination == null) {
                delivery = CompletableFuture.completedFuture(SendResult.NOT_CONNECTED);
            } else if (!destination.allowedReceiveNamespaces.contains(namespace(message.channel()))) {
                delivery = CompletableFuture.completedFuture(SendResult.REJECTED);
            } else {
                Message forwarded = retarget(message, target);
                delivery = exchange.sendTo(destination, forwarded, remaining(deadline));
            }
        }
        delivery.whenComplete((result, failure) -> {
            if (failure != null) respondError(origin, operationId, asMessagingException(failure), deadline);
            else respondSend(origin, operationId, result, deadline);
            origin.inboundRequests.decrementAndGet();
        });
    }

    private void routePublish(Connection origin, long operationId, Message event, long deadline) {
        List<Connection> destinations = leases.connectedBackends().stream()
                .filter(candidate -> candidate.allowedReceiveNamespaces.contains(namespace(event.channel())))
                .toList();
        if (destinations.size() + 1 > MessageCodec.MAX_PUBLISH_RESULTS) {
            respondError(origin, operationId, new MessagingException(MessagingException.Code.REJECTED,
                    "publish exceeds the maximum of " + MessageCodec.MAX_PUBLISH_RESULTS + " authorized nodes"), deadline);
            origin.inboundRequests.decrementAndGet();
            return;
        }
        Map<Endpoint, SendResult> results = new LinkedHashMap<>();
        results.put(Endpoint.proxy(), messaging.receiveEvent(event));
        List<CompletableFuture<Void>> completions = new ArrayList<>();
        for (Connection destination : destinations) {
            Endpoint endpoint = Endpoint.backend(destination.name);
            Message forwarded = retarget(event, endpoint);
            CompletableFuture<Void> completion = new CompletableFuture<>();
            completions.add(completion);
            exchange.sendTo(destination, forwarded, remaining(deadline)).whenComplete((status, failure) -> {
                synchronized (results) {
                    results.put(endpoint, failure == null ? status : sendFailure(failure));
                }
                completion.complete(null);
            });
        }
        CompletableFuture.allOf(completions.toArray(CompletableFuture[]::new)).whenComplete((ignored, failure) -> {
            Map<Endpoint, SendResult> copy;
            synchronized (results) { copy = new LinkedHashMap<>(results); }
            try { writer.writeAsync(origin, MessageCodec.published(operationId, new PublishResult(event.id(), copy)), deadline); }
            catch (IOException invalid) { writer.writeAsync(origin, errorFrame(operationId, invalid), deadline); }
            finally { origin.inboundRequests.decrementAndGet(); }
        });
    }

    private void routeRequest(Connection origin, long operationId, Message request, long deadline) {
        CompletionStage<Message> response;
        Endpoint target = request.target();
        if (target.isProxy()) {
            response = dispatchProxyRequest(origin, request, deadline);
        } else {
            Connection destination = leases.current(target.backendName());
            if (destination == null) {
                response = failed(new MessagingException(MessagingException.Code.NOT_CONNECTED,
                        "target backend is not connected"));
            } else if (!destination.allowedReceiveNamespaces.contains(namespace(request.channel()))) {
                response = failed(new MessagingException(MessagingException.Code.REJECTED,
                        "target backend does not allow this message namespace"));
            } else {
                response = exchange.requestFrom(destination, request, remaining(deadline));
            }
        }
        response.whenComplete((reply, failure) -> {
            if (failure != null) respondError(origin, operationId, asMessagingException(failure), deadline);
            else {
                try { writer.writeAsync(origin, MessageCodec.reply(operationId, reply), deadline); }
                catch (IOException invalid) { writer.writeAsync(origin, errorFrame(operationId, invalid), deadline); }
            }
            origin.inboundRequests.decrementAndGet();
        });
    }

    private CompletionStage<Message> dispatchProxyRequest(Connection origin, Message request, long deadline) {
        Duration timeout = remaining(deadline);
        if (timeout.isZero()) return failed(new MessagingException(MessagingException.Code.TIMED_OUT,
                "request expired before proxy dispatch"));
        return messaging.receiveRequest(retarget(request, Endpoint.proxy()), timeout);
    }

    private void respondSend(Connection connection, long operationId, SendResult result, long deadline) {
        try { writer.writeAsync(connection, MessageCodec.sendResult(operationId, result), deadline); }
        catch (IOException invalid) { writer.writeAsync(connection, errorFrame(operationId, invalid), deadline); }
    }

    private void respondError(Connection connection, long operationId, MessagingException error, long deadline) {
        try { writer.writeAsync(connection, MessageCodec.error(operationId, error.code(), safeDetail(error)), deadline); }
        catch (IOException invalid) { writer.writeAsync(connection, errorFrame(operationId, invalid), deadline); }
    }
}

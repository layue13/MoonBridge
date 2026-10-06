package dev.moonbridge.core.plugin;

import dev.moonbridge.messaging.Endpoint;
import dev.moonbridge.messaging.Message;
import dev.moonbridge.messaging.MessageKind;
import dev.moonbridge.messaging.MessagingException;
import dev.moonbridge.messaging.PublishResult;
import dev.moonbridge.messaging.SendResult;
import dev.moonbridge.messaging.internal.LocalMessaging;
import dev.moonbridge.core.control.BackendChannelTransport;

import java.time.Duration;
import java.util.function.Supplier;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

    final class HostMessagingOutbound implements LocalMessaging.Outbound {
    private final Supplier<LocalMessaging> localMessaging;
    private final Supplier<BackendChannelTransport> transportSupplier;

    HostMessagingOutbound(Supplier<LocalMessaging> localMessaging, Supplier<BackendChannelTransport> transport) {
        this.localMessaging = localMessaging;
        this.transportSupplier = transport;
    }

        @Override public CompletionStage<SendResult> send(Message message) {
            if (!Endpoint.proxy().equals(message.source()) || message.kind() != MessageKind.EVENT
                    || message.target() == null) {
                return CompletableFuture.failedFuture(new MessagingException(
                        MessagingException.Code.REJECTED, "invalid proxy message send"));
            }
            if (message.target().isProxy()) {
                return CompletableFuture.completedFuture(localMessaging.get().receiveEvent(message));
            }
            BackendChannelTransport transport = transportSupplier.get();
            if (transport == null) return CompletableFuture.completedFuture(SendResult.NOT_CONNECTED);
            try {
                return Objects.requireNonNull(transport.send(message), "transport send stage");
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        @Override public CompletionStage<Message> request(Message message, Duration timeout) {
            if (!Endpoint.proxy().equals(message.source()) || message.kind() != MessageKind.REQUEST
                    || message.target() == null) {
                return CompletableFuture.failedFuture(new MessagingException(
                        MessagingException.Code.REJECTED, "invalid proxy message request"));
            }
            if (message.target().isProxy()) return localMessaging.get().receiveRequest(message, timeout);
            BackendChannelTransport transport = transportSupplier.get();
            if (transport == null) return CompletableFuture.failedFuture(new MessagingException(
                    MessagingException.Code.NOT_CONNECTED, "backend control transport is not available"));
            try {
                return Objects.requireNonNull(transport.request(message, timeout), "transport request stage");
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        @Override public CompletionStage<PublishResult> publish(Message event) {
            if (!Endpoint.proxy().equals(event.source()) || event.kind() != MessageKind.EVENT
                    || event.target() != null) {
                return CompletableFuture.failedFuture(new MessagingException(
                        MessagingException.Code.REJECTED, "invalid proxy event publication"));
            }
            BackendChannelTransport transport = transportSupplier.get();
            if (transport == null) return CompletableFuture.completedFuture(new PublishResult(
                    event.id(), Map.of(Endpoint.proxy(), localMessaging.get().receiveEvent(event))));
            final CompletionStage<PublishResult> remote;
            try {
                remote = Objects.requireNonNull(transport.publish(event), "transport publish stage");
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
            CompletableFuture<PublishResult> combinedResult = new CompletableFuture<>();
            remote.whenComplete((result, failure) -> {
                if (combinedResult.isCancelled()) return;
                if (failure != null) {
                    combinedResult.completeExceptionally(failure);
                    return;
                }
                if (!event.id().equals(result.messageId()) || result.results().containsKey(Endpoint.proxy())) {
                    combinedResult.completeExceptionally(new MessagingException(MessagingException.Code.PROTOCOL_ERROR,
                            "transport returned an invalid publish result"));
                    return;
                }
                Map<Endpoint, SendResult> combined = new java.util.LinkedHashMap<>();
                combined.put(Endpoint.proxy(), localMessaging.get().receiveEvent(event));
                combined.putAll(result.results());
                combinedResult.complete(new PublishResult(event.id(), combined));
            });
            combinedResult.whenComplete((ignored, failure) -> {
                if (combinedResult.isCancelled()) remote.toCompletableFuture().cancel(false);
            });
            return combinedResult;
        }

    }

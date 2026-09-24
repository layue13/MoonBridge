package dev.strataproxy.plugin.service;

import dev.strataproxy.backend.api.BackendMessage;
import dev.strataproxy.backend.api.BackendMessageRegistration;
import dev.strataproxy.backend.api.DeliveryMode;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Channel subscription and publishing service for proxy plugins. */
public interface ProxyChannelService {
    /** Publishes to every subscribed backend agent; the result contains a broker-generated message ID. */
    default CompletionStage<ChannelPublishResult> publish(String channel, byte[] payload, String correlationId, DeliveryMode mode) {
        return publish(channel, payload, correlationId, "", mode);
    }

    CompletionStage<ChannelPublishResult> publish(String channel, byte[] payload, String correlationId,
                                                  String idempotencyKey, DeliveryMode mode);

    /** Publishes to one subscribed backend agent; the backend name resolves to its current instance. */
    default CompletionStage<ChannelPublishResult> publishTo(String backendName, String channel, byte[] payload,
                                                            String correlationId, DeliveryMode mode) {
        return publishTo(backendName, channel, payload, correlationId, "", mode);
    }

    CompletionStage<ChannelPublishResult> publishTo(String backendName, String channel, byte[] payload,
                                                   String correlationId, String idempotencyKey, DeliveryMode mode);

    /** Receives messages published by authenticated backend agents. */
    BackendMessageRegistration subscribe(String channel, Consumer<BackendMessage> listener);
}

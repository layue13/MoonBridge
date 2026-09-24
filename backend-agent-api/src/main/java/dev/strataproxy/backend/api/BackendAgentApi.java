package dev.strataproxy.backend.api;

import java.util.concurrent.CompletionStage;

/**
 * Platform-independent messaging service published by a StrataProxy backend agent.
 *
 * <p>Server-platform adapters such as Bukkit or Forge provide the implementation.
 * Business plugins should depend only on this API, never on an adapter class.</p>
 */
public interface BackendAgentApi {
    /** Publishes an opaque payload to the proxy broker using best-effort delivery. */
    default CompletionStage<BackendMessageResult> publish(String channel, byte[] payload) {
        return publish(channel, payload, "", DeliveryMode.BEST_EFFORT);
    }

    /** Publishes an opaque payload with optional correlation and delivery policy. */
    default CompletionStage<BackendMessageResult> publish(String channel, byte[] payload, String correlationId, DeliveryMode mode) {
        return publish(channel, payload, correlationId, "", mode);
    }

    /** Reusing a nonempty idempotency key for the same publication returns its original message ID. */
    CompletionStage<BackendMessageResult> publish(String channel, byte[] payload, String correlationId,
                                                  String idempotencyKey, DeliveryMode mode);

    /**
     * Subscribes to proxy-broker messages on one channel.
     * Call {@link BackendMessageRegistration#close()} when the listener is no
     * longer needed.
     */
    BackendMessageRegistration listen(String channel, BackendMessageListener listener);
}

package dev.strataproxy.backend.api;

import java.util.concurrent.CompletionStage;

/**
 * Platform-independent messaging service published by a StrataProxy backend agent.
 *
 * <p>Server-platform adapters such as Bukkit or Forge provide the implementation.
 * Business plugins should depend only on this API, never on an adapter class.</p>
 */
public interface BackendAgentApi {
    /**
     * Sends an opaque plugin-message payload through one player's proxy connection.
     * Completion means the platform adapter accepted the payload for writing; it
     * does not confirm client or proxy-plugin processing.
     */
    CompletionStage<BackendMessageResult> send(BackendPlayer player, String channel, byte[] payload);

    /**
     * Registers a listener for proxy-to-backend plugin messages on one channel.
     * Call {@link BackendMessageRegistration#close()} when the listener is no
     * longer needed.
     */
    BackendMessageRegistration listen(String channel, BackendMessageListener listener);
}

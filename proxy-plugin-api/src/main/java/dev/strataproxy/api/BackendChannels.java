package dev.strataproxy.api;

import java.util.concurrent.CompletionStage;

/** Sends messages to, and registers request handlers for, connected backend instances. */
public interface BackendChannels {
    /**
     * Registers the handler for a channel. A channel may have only one handler at a time.
     * Closing the returned subscription unregisters the handler.
     */
    BackendChannelSubscription subscribe(String channel, BackendMessageHandler handler);

    /**
     * Sends a one-way message to a backend. A successful {@link BackendSendResult#SENT} means
     * the message was written to the control connection; it does not confirm application handling.
     * The implementation copies {@code payload} before this method returns, so the caller may
     * reuse or mutate its array after the call.
     */
    CompletionStage<BackendSendResult> send(String backendName, String channel, byte[] payload);

    /**
     * Sends a request and completes with the backend handler's response. The stage completes
     * exceptionally if the backend is unavailable, the default five-second request deadline
     * expires, or transport fails. The implementation copies {@code payload} before this method
     * returns, so the caller may reuse or mutate its array after the call.
     */
    CompletionStage<byte[]> request(String backendName, String channel, byte[] payload);
}

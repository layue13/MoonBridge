package dev.strataproxy.messaging;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Operations and subscriptions for one named application channel. Channels are routing and access-control
 * names; application message types belong in the payload schema.
 */
public interface MessageChannel {
    String name();

    /**
     * Adds a local listener for events. Every currently registered listener receives its own event callback;
     * event subscriptions do not handle requests or send replies.
     */
    Subscription subscribe(Consumer<Message> listener);

    /**
     * Registers the single request handler for this channel on this node. A second active handler for the
     * same local channel is rejected; this keeps request delivery and reply ownership unambiguous.
     */
    Subscription onRequest(MessageHandler handler);

    /**
     * Sends a one-way event to one endpoint. {@link SendResult#ACCEPTED} means the target accepted the event
     * into its bounded processing queue; it does not mean a listener ran or completed successfully. Routing
     * outcomes such as no subscriber, backpressure, disconnection, rejection, and timeout are returned in the
     * receipt. A timeout means no receipt arrived by the deadline and does not prove the event was not delivered.
     * Closing the scope completes the operation exceptionally with {@link MessagingException.Code#CLOSED};
     * cancelling the returned future cancels the operation on a best-effort basis.
     */
    CompletionStage<SendReceipt> send(Endpoint target, byte[] payload);

    /**
     * Publishes one event to the current eligible online endpoints, including the publisher. The returned
     * result has one status per candidate endpoint and may report {@link SendResult#NO_SUBSCRIBER}. Delivery
     * is live-only and is not durably retained.
     */
    CompletionStage<PublishResult> publish(byte[] payload);

    /**
     * Sends a request to one endpoint and returns its correlated reply. The default deadline is five seconds.
     */
    CompletionStage<Message> request(Endpoint target, byte[] payload);

    /**
     * Sends a request to one endpoint and returns its correlated reply. The deadline starts at this call,
     * includes local queueing and transport time, and must be positive and no more than 60 seconds. The
     * returned REPLY has a new message ID and its {@code replyTo} points to this request's ID.
     */
    CompletionStage<Message> request(Endpoint target, byte[] payload, Duration timeout);
}

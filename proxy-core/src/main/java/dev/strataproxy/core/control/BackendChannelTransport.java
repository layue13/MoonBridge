package dev.strataproxy.core.control;

import dev.strataproxy.messaging.Message;
import dev.strataproxy.messaging.PublishResult;
import dev.strataproxy.messaging.SendResult;
import java.time.Duration;
import java.util.concurrent.CompletionStage;

/** Outbound operations exposed to the proxy plugin host. */
public interface BackendChannelTransport {
    CompletionStage<SendResult> send(Message message);

    CompletionStage<Message> request(Message message, Duration timeout);

    CompletionStage<PublishResult> publish(Message event);
}

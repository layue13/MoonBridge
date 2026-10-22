package dev.moonbridge.core.control;

import dev.moonbridge.messaging.Message;
import dev.moonbridge.messaging.PublishResult;
import dev.moonbridge.messaging.SendResult;
import java.time.Duration;
import java.util.concurrent.CompletionStage;

/** Outbound operations exposed to the proxy plugin host. */
public interface BackendChannelTransport {
    CompletionStage<SendResult> send(Message message);

    CompletionStage<Message> request(Message message, Duration timeout);

    CompletionStage<PublishResult> publish(Message event);
}

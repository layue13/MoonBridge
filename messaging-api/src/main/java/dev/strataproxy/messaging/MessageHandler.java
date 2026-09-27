package dev.strataproxy.messaging;

import java.util.concurrent.CompletionStage;

/**
 * Handles one request and asynchronously supplies its reply payload. The handler is invoked on its owning
 * scope's executor, never on the transport reader. Its returned stage supplies only the payload; the host
 * creates the correlated REPLY envelope.
 */
@FunctionalInterface
public interface MessageHandler {
    CompletionStage<byte[]> handle(Message request) throws Exception;
}

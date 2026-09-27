package dev.strataproxy.api;

import java.util.concurrent.CompletionStage;

/** Handles a request received from a backend and produces its response payload. */
@FunctionalInterface
public interface BackendMessageHandler {
    CompletionStage<byte[]> handle(BackendMessage message);
}

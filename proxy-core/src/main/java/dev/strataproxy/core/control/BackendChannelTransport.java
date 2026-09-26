package dev.strataproxy.core.control;

import dev.strataproxy.api.BackendSendResult;
import java.util.concurrent.CompletionStage;

/** Outbound operations exposed to the proxy plugin host. */
public interface BackendChannelTransport {
    CompletionStage<BackendSendResult> send(String backendName, String channel, byte[] payload);

    CompletionStage<byte[]> request(String backendName, String channel, byte[] payload);
}

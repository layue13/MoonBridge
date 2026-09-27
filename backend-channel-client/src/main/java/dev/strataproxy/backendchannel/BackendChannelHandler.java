package dev.strataproxy.backendchannel;

import java.util.concurrent.CompletionStage;

/** Handles an inbound request or one-way message for a registered channel name. */
public interface BackendChannelHandler {
    CompletionStage<byte[]> handle(byte[] payload) throws Exception;
}

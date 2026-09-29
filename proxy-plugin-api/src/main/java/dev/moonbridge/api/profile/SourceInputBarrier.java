package dev.moonbridge.api.profile;

import java.util.concurrent.CompletionStage;

/**
 * Proxy ingress barrier for a transfer. Completion only proves that MoonBridge stopped
 * forwarding newly received client frames and drained writes it had already accepted;
 * the coordinator must still obtain a source-server processing barrier before capture.
 */
public interface SourceInputBarrier {
    CompletionStage<Void> proxyIngressStopped();

    boolean isHeld();
}

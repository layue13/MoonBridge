package dev.strataproxy.api;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletionStage;

/** Checks a newly accepted connection using the peer address observed by the proxy. */
@FunctionalInterface
public interface ConnectionCheck {
    /**
     * Returns whether the connection may continue. The proxy invokes this off its I/O event loop,
     * applies one deadline across all registered checks, and may cancel the returned stage when
     * the client disconnects or the plugin host closes. Cancellation is best effort.
     */
    CompletionStage<AccessDecision> check(InetSocketAddress remoteAddress);
}

package dev.strataproxy.api;

import java.util.concurrent.CompletionStage;

/** Checks a player login after the proxy has established its mode-specific identity. */
@FunctionalInterface
public interface LoginCheck {
    /**
     * Returns whether the player may proceed to initial backend placement. In offline mode the
     * player identity is derived from the client-provided name and is not authenticated; inspect
     * {@link LoginRequest#authenticated()} before treating it as verified. The proxy invokes this
     * off its I/O event loop and may cancel the returned stage when the client disconnects or the
     * plugin host closes. Cancellation is best effort.
     */
    CompletionStage<AccessDecision> check(LoginRequest request);
}

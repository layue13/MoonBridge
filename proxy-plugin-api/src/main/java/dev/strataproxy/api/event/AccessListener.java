package dev.strataproxy.api.event;

import dev.strataproxy.api.AccessDecision;
import java.util.concurrent.CompletionStage;

/**
 * Handles an access event off the proxy I/O thread. Exceptions, failed stages, null results and
 * expiry of the shared phase deadline deny access. The host may cancel the returned future when
 * the client disconnects, the deadline expires or the host closes; cancellation is best effort.
 */
@FunctionalInterface
public interface AccessListener<E extends AccessEvent> {
    CompletionStage<AccessDecision> onEvent(E event);
}

package dev.strataproxy.plugin.route;

/**
 * Registers one initial route policy and one policy per transfer route key.
 * The proxy invokes policies away from Netty event-loop threads and waits for
 * their asynchronous result until the supplied timeout. Closing a registration
 * removes it; another plugin may then register that route.
 */
public interface RouteService {
    /**
     * Handles first-backend selection. Only one plugin may own this handler.
     * Passing leaves selection to the proxy's minimal default.
     */
    AutoCloseable registerInitial(java.time.Duration timeout, RoutePolicy policy);

    /**
     * Handles an explicit {@code players().route(player, routeKey)} request.
     * Only one plugin may own a key. Passing means no route was selected.
     */
    AutoCloseable registerTransfer(String routeKey, java.time.Duration timeout, RoutePolicy policy);
}

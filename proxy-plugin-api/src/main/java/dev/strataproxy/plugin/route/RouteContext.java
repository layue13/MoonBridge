package dev.strataproxy.plugin.route;

import dev.strataproxy.plugin.service.PlayerIdentity;

/**
 * Immutable snapshot of the information available to a route policy.
 *
 * <p>{@code playerIdentity} and {@code playerName} may be absent at stages
 * where the proxy has not established an authenticated player identity.
 * {@code currentServer} is empty for initial backend selection. Plugins can
 * obtain current {@code ServerView} snapshots independently through
 * {@code PluginContext.servers()}.</p>
 *
 * @param stage lifecycle point that requested a route
 * @param routeKey optional logical route key, such as a configured listener or
 *                 game mode; empty when no key applies
 * @param requestedHost host requested by the client during initial login;
 *                      empty when unavailable
 * @param protocolVersion negotiated or requested protocol version
 * @param remoteAddress remote peer address in the proxy's textual address form
 * @param playerIdentity stable identity for the current player connection, or
 *                       {@code null} when not yet available
 * @param playerName player name when known, otherwise empty
 * @param currentServer current backend name for an already-connected player,
 *                      otherwise empty
 */
public record RouteContext(
        RouteStage stage,
        String routeKey,
        String requestedHost,
        int protocolVersion,
        String remoteAddress,
        PlayerIdentity playerIdentity,
        String playerName,
        String currentServer) {

    public RouteContext {
        if (stage == null) {
            throw new IllegalArgumentException("stage must not be null");
        }
        routeKey = normalize(routeKey);
        requestedHost = normalize(requestedHost);
        remoteAddress = normalize(remoteAddress);
        playerName = normalize(playerName);
        currentServer = normalize(currentServer);
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }
}

package dev.strataproxy.plugin.route;

import dev.strataproxy.plugin.service.PlayerIdentity;

/**
 * Immutable snapshot of the information available to a route policy.
 *
 * <p>{@code currentServer} is empty for initial backend selection. Plugins can
 * obtain current {@code ServerView} snapshots independently through
 * {@code PluginContext.servers()}.</p>
 *
 * @param stage lifecycle point that requested a route
 * @param routeKey requested host at initial login, or the explicit transfer key
 * @param protocolVersion negotiated or requested protocol version
 * @param playerIdentity identity for this exact player connection
 * @param playerName player name
 * @param currentServer current backend name for an already-connected player,
 *                      otherwise empty
 */
public record RouteContext(
        RouteStage stage,
        String routeKey,
        int protocolVersion,
        PlayerIdentity playerIdentity,
        String playerName,
        String currentServer) {

    public RouteContext {
        if (stage == null) {
            throw new IllegalArgumentException("stage must not be null");
        }
        routeKey = normalize(routeKey);
        playerName = normalize(playerName);
        currentServer = normalize(currentServer);
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }
}

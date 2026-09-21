package dev.strataproxy.plugin.service;

/**
 * Read-only view of an online player.
 *
 * @param name player name
 * @param serverName backend currently serving the player
 * @param remoteAddress client remote address as observed by the proxy
 */
public record PlayerView(PlayerIdentity identity, String name, String serverName, String remoteAddress) {
    public PlayerView(String name, String serverName, String remoteAddress) {
        this(new PlayerIdentity(null, ""), name, serverName, remoteAddress);
    }
}

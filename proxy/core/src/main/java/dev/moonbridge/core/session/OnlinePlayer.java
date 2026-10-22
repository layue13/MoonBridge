package dev.moonbridge.core.session;

import dev.moonbridge.api.PlayerView;

import java.net.InetSocketAddress;
import java.time.Instant;

/**
 * An online player as the admin console shows it: the plugin-visible view plus how and since when the player is
 * connected. {@code address} is the player's address as {@code SessionChannels.clientAddress} reports it.
 */
public record OnlinePlayer(PlayerView view, InetSocketAddress address, boolean webSocket, Instant connectedAt) { }

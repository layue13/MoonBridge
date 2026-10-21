package dev.moonbridge.bukkit;

import java.util.Optional;
import org.bukkit.entity.Player;

/** Exposes authenticated proxy-session identity attached to the actual Bukkit player object. */
public interface BukkitSessionService {
    /** Returns empty when the forwarding proof is missing, invalid, expired, replayed, or stale. */
    Optional<BackendPlayerSession> find(Player player);

    /** Matches all connection identity fields, rather than UUID alone. */
    default boolean matches(Player player, java.util.UUID proxyEpoch, long connectionId) {
        if (player == null || proxyEpoch == null) return false;
        Optional<BackendPlayerSession> found = find(player);
        return found.isPresent() && found.get().getProxyEpoch().equals(proxyEpoch)
                && found.get().getConnectionId() == connectionId;
    }
}

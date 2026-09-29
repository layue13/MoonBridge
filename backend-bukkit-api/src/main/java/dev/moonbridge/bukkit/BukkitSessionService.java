package dev.moonbridge.bukkit;

import java.util.Optional;
import org.bukkit.entity.Player;

/** Exposes authenticated proxy-session identity attached to the actual Bukkit player object. */
public interface BukkitSessionService {
    /** Returns empty when the forwarding proof is missing, invalid, expired, replayed, or stale. */
    Optional<BackendPlayerSession> find(Player player);

    /**
     * Verifies and consumes a forwarded proxy proof before Bukkit creates or reads a player entity.
     * The host checks its currently registered backend/proxy epochs and expected UUID. A successful
     * result is bound at PlayerJoin by the host exactly once; repeating this call for the same proof
     * returns empty.
     */
    Optional<BackendPlayerSession> preflight(String forwardedProofToken, java.util.UUID expectedPlayerId);

    /** Bind the exact one-use preflight result to the player created for this login. */
    boolean bindPreverified(Player player, BackendPlayerSession session);

    /** Remove a binding if admission fails before Bukkit join. */
    void unbindPreverified(Player player, BackendPlayerSession session);

    /** Matches all connection identity fields, rather than UUID alone. */
    default boolean matches(Player player, java.util.UUID proxyEpoch, long connectionId) {
        if (player == null || proxyEpoch == null) return false;
        Optional<BackendPlayerSession> found = find(player);
        return found.isPresent() && found.get().getProxyEpoch().equals(proxyEpoch)
                && found.get().getConnectionId() == connectionId;
    }
}

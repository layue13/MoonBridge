package dev.moonbridge.bukkit;

import java.util.Optional;
import org.bukkit.entity.Player;

/** Exposes authenticated proxy-session identity attached to the actual Bukkit player object. */
public interface BukkitSessionService {
    /** Returns empty when the forwarding proof is missing, invalid, expired, replayed, or stale. */
    Optional<BackendPlayerSession> find(Player player);

    /**
     * Verifies and binds the forwarded session proof for this exact player object before it is
     * online. Repeated calls for the same login incarnation return the same binding. A successful
     * result does not make {@link #find(Player)} succeed until Bukkit's join event promotes the
     * binding. Call from the server thread while the login profile is available; callers should
     * reject the login when authentication is empty.
     *
     * @return the authenticated binding, or empty when the proof is missing, invalid, expired,
     *         replayed, stale, or the bounded provisional table cannot admit another login
     */
    default Optional<BackendPlayerSession> authenticate(Player player) {
        return Optional.empty();
    }

    /** Matches all connection identity fields, rather than UUID alone. */
    default boolean matches(Player player, java.util.UUID proxyEpoch, long connectionId) {
        if (player == null || proxyEpoch == null) return false;
        Optional<BackendPlayerSession> found = find(player);
        return found.isPresent() && found.get().getProxyEpoch().equals(proxyEpoch)
                && found.get().getConnectionId() == connectionId;
    }
}

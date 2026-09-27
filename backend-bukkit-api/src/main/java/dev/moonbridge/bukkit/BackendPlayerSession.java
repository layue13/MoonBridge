package dev.moonbridge.bukkit;

import java.util.Objects;
import java.util.UUID;

/** Exact proxy connection proof bound to one Bukkit login incarnation. */
public final class BackendPlayerSession {
    private final UUID playerId;
    private final UUID proxyEpoch;
    private final long connectionId;
    private final String backendName;
    private final long backendEpoch;
    private final UUID joinEpoch;

    public BackendPlayerSession(UUID playerId, UUID proxyEpoch, long connectionId,
                                String backendName, long backendEpoch, UUID joinEpoch) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.proxyEpoch = Objects.requireNonNull(proxyEpoch, "proxyEpoch");
        this.backendName = Objects.requireNonNull(backendName, "backendName");
        this.joinEpoch = Objects.requireNonNull(joinEpoch, "joinEpoch");
        if (connectionId < 0) throw new IllegalArgumentException("connectionId must be non-negative");
        if (backendEpoch < 0) throw new IllegalArgumentException("backendEpoch must be non-negative");
        if (backendName.trim().isEmpty()) throw new IllegalArgumentException("backendName must not be blank");
        this.connectionId = connectionId;
        this.backendEpoch = backendEpoch;
    }

    public UUID getPlayerId() { return playerId; }
    public UUID getProxyEpoch() { return proxyEpoch; }
    public long getConnectionId() { return connectionId; }
    public String getBackendName() { return backendName; }
    public long getBackendEpoch() { return backendEpoch; }
    public UUID getJoinEpoch() { return joinEpoch; }
}

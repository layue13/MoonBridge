package dev.strataproxy.api.server;

/**
 * Runtime load sample for a backend server.
 *
 * @param players current player count
 * @param softCapacity capacity where routers start reducing this server's score; zero disables pressure
 * @param hardCapacity capacity where the server is considered full; zero means unlimited
 * @param inboundBytesPerSecond recent inbound throughput from clients to backend
 * @param outboundBytesPerSecond recent outbound throughput from backend to clients
 * @param packetsPerSecond recent packet rate across both directions
 * @param eventLoopDelayMillis measured backend or relay event-loop delay in milliseconds
 */
public record ServerLoad(
        int players,
        int softCapacity,
        int hardCapacity,
        long inboundBytesPerSecond,
        long outboundBytesPerSecond,
        long packetsPerSecond,
        double eventLoopDelayMillis) {
    /**
     * Validates and normalizes record components.
     */
    public ServerLoad {
        if (players < 0 || softCapacity < 0 || hardCapacity < 0) {
            throw new IllegalArgumentException("capacity values must be non-negative");
        }
        if (softCapacity > hardCapacity && hardCapacity != 0) {
            throw new IllegalArgumentException("softCapacity must be <= hardCapacity");
        }
    }

    /**
     * Computes load pressure relative to soft capacity.
     *
     * @return ratio of players to soft capacity, capped at {@code 2.0}; zero when soft capacity is disabled
     */
    public double capacityPressure() {
        if (softCapacity == 0) {
            return 0.0d;
        }
        return Math.min(2.0d, (double) players / softCapacity);
    }

    /**
 * Documents this public API element.
 *
     * @return {@code true} when hard capacity is enabled and current players meet or exceed it
     */
    public boolean isHardFull() {
        return hardCapacity > 0 && players >= hardCapacity;
    }
}

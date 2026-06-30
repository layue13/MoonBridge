package dev.strataproxy.api.server;

public record ServerLoad(
        int players,
        int softCapacity,
        int hardCapacity,
        long inboundBytesPerSecond,
        long outboundBytesPerSecond,
        long packetsPerSecond,
        double eventLoopDelayMillis) {
    public ServerLoad {
        if (players < 0 || softCapacity < 0 || hardCapacity < 0) {
            throw new IllegalArgumentException("capacity values must be non-negative");
        }
        if (softCapacity > hardCapacity && hardCapacity != 0) {
            throw new IllegalArgumentException("softCapacity must be <= hardCapacity");
        }
    }

    public double capacityPressure() {
        if (softCapacity == 0) {
            return 0.0d;
        }
        return Math.min(2.0d, (double) players / softCapacity);
    }

    public boolean isHardFull() {
        return hardCapacity > 0 && players >= hardCapacity;
    }
}

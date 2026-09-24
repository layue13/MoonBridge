package dev.strataproxy.plugin.service;

/** Immutable observed load sample visible to proxy plugins. */
public record ServerLoadView(
        int players,
        int softCapacity,
        int hardCapacity,
        long inboundBytesPerSecond,
        long outboundBytesPerSecond,
        long packetsPerSecond,
        double eventLoopDelayMillis) {
    public boolean isHardFull() {
        return hardCapacity > 0 && players >= hardCapacity;
    }
}

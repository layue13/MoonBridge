package dev.strataproxy.plugin.service;

/** Immutable observed load sample visible to proxy plugins. */
public record ServerLoadView(
        int players,
        long inboundBytesPerSecond,
        long outboundBytesPerSecond) {
}

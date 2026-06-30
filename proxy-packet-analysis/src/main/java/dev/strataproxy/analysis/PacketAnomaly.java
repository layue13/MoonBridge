package dev.strataproxy.analysis;

import dev.strataproxy.protocol.PacketView;

import java.time.Instant;

public record PacketAnomaly(
        String ruleId,
        PacketView packet,
        AnomalyAction action,
        String explanation,
        Instant timestamp) {
    public PacketAnomaly {
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }
}

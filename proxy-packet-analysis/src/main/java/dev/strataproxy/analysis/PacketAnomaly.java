package dev.strataproxy.analysis;

import dev.strataproxy.protocol.PacketView;

import java.time.Instant;

/**
 * Finding produced by packet analysis.
 *
 * @param ruleId stable rule identifier
 * @param packet packet that triggered the finding
 * @param action recommended response
 * @param explanation human-readable diagnostic message
 * @param timestamp time the anomaly was produced
 */
public record PacketAnomaly(
        String ruleId,
        PacketView packet,
        AnomalyAction action,
        String explanation,
        Instant timestamp) {
    /**
     * Validates and normalizes record components.
     */
    public PacketAnomaly {
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }
}

package dev.strataproxy.analysis;

import dev.strataproxy.protocol.PacketView;

import java.util.Optional;

/**
 * Predicate-like packet analysis rule.
 */
public interface PacketRule {
    /**
     * Evaluates a packet.
     *
     * @param packet observed packet
     * @return anomaly when the rule is triggered
     */
    Optional<PacketAnomaly> evaluate(PacketView packet);
}

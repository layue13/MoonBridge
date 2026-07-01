package dev.strataproxy.analysis;

import dev.strataproxy.protocol.PacketView;

import java.util.List;

/**
 * Applies packet anomaly rules and returns all findings for a packet.
 */
public final class PacketAnalyzer {
    private final List<PacketRule> rules;

    /**
 * Documents this public API element.
 *
     * @param rules rules to apply in order
     */
    public PacketAnalyzer(List<PacketRule> rules) {
        this.rules = List.copyOf(rules);
    }

    /**
     * Runs all configured rules for a packet.
     *
     * @param packet observed packet
     * @return anomalies produced by the rules
     */
    public List<PacketAnomaly> analyze(PacketView packet) {
        return rules.stream().flatMap(rule -> rule.evaluate(packet).stream()).toList();
    }
}

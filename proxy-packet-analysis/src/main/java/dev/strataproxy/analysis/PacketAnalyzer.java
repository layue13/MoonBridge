package dev.strataproxy.analysis;

import dev.strataproxy.protocol.PacketView;

import java.util.List;

public final class PacketAnalyzer {
    private final List<PacketRule> rules;

    public PacketAnalyzer(List<PacketRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public List<PacketAnomaly> analyze(PacketView packet) {
        return rules.stream().flatMap(rule -> rule.evaluate(packet).stream()).toList();
    }
}

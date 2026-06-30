package dev.strataproxy.analysis;

import dev.strataproxy.protocol.PacketView;
import dev.strataproxy.protocol.ProtocolState;

import java.time.Instant;
import java.util.Optional;

public record MaxPacketSizeRule(String id, ProtocolState state, int maxBytes, AnomalyAction action) implements PacketRule {
    @Override
    public Optional<PacketAnomaly> evaluate(PacketView packet) {
        if (packet.state() == state && packet.rawSize() > maxBytes) {
            var explanation = "packet exceeded " + maxBytes + " bytes in " + state + " state";
            return Optional.of(new PacketAnomaly(id, packet, action, explanation, Instant.now()));
        }
        return Optional.empty();
    }
}

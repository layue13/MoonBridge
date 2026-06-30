package dev.strataproxy.analysis;

import dev.strataproxy.protocol.PacketView;

import java.util.Optional;

public interface PacketRule {
    Optional<PacketAnomaly> evaluate(PacketView packet);
}

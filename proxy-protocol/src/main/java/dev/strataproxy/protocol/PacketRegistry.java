package dev.strataproxy.protocol;

import java.util.Optional;

public interface PacketRegistry {
    Optional<PacketDefinition> find(PacketView packet);
}

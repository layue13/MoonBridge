package dev.strataproxy.protocol;

import java.util.List;
import java.util.Optional;

public final class InMemoryPacketRegistry implements PacketRegistry {
    private final List<PacketDefinition> definitions;

    public InMemoryPacketRegistry(List<PacketDefinition> definitions) {
        this.definitions = List.copyOf(definitions);
    }

    @Override
    public Optional<PacketDefinition> find(PacketView packet) {
        return definitions.stream()
                .filter(definition -> definition.id() == packet.packetId())
                .filter(definition -> definition.state() == packet.state())
                .filter(definition -> definition.direction() == packet.direction())
                .filter(definition -> definition.appliesTo(packet.protocolVersion()))
                .findFirst();
    }
}

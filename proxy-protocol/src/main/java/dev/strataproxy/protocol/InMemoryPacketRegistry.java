package dev.strataproxy.protocol;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Packet registry backed by an immutable in-memory list of definitions.
 */
public final class InMemoryPacketRegistry implements PacketRegistry {
    private final Map<Key, List<PacketDefinition>> definitionsByKey;

    /**
 * Documents this public API element.
 *
     * @param definitions packet metadata definitions to search
     */
    public InMemoryPacketRegistry(List<PacketDefinition> definitions) {
        var indexed = new HashMap<Key, ArrayList<PacketDefinition>>();
        for (var definition : List.copyOf(definitions)) {
            var key = new Key(definition.id(), definition.state(), definition.direction());
            indexed.computeIfAbsent(key, ignored -> new ArrayList<>()).add(definition);
        }
        var immutable = new HashMap<Key, List<PacketDefinition>>();
        for (var entry : indexed.entrySet()) {
            immutable.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        this.definitionsByKey = Map.copyOf(immutable);
    }

    @Override
    /** Provides find. */
    public Optional<PacketDefinition> find(PacketView packet) {
        var key = new Key(packet.packetId(), packet.state(), packet.direction());
        return definitionsByKey.getOrDefault(key, List.of()).stream()
                .filter(definition -> definition.appliesTo(packet.protocolVersion()))
                .findFirst();
    }

    private record Key(int id, ProtocolState state, PacketDirection direction) {
    }
}

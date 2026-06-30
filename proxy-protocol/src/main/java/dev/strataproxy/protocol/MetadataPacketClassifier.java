package dev.strataproxy.protocol;

import java.util.Objects;

public final class MetadataPacketClassifier {
    private final PacketRegistry registry;

    public MetadataPacketClassifier(PacketRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public PacketClassification classify(PacketView packet, boolean policySubscriberPresent) {
        var definition = registry.find(packet).orElse(null);
        if (definition == null) {
            return new PacketClassification(packet, null, false, true, false, true);
        }

        var rewrite = definition.has(PacketFlag.REQUIRES_ENTITY_REWRITE);
        var inspect = policySubscriberPresent || definition.has(PacketFlag.REQUIRES_INSPECTION);
        var fastForward = definition.has(PacketFlag.CAN_FAST_FORWARD) && !rewrite && !inspect;
        var deepDecode = !fastForward;

        return new PacketClassification(packet, definition, fastForward, deepDecode, rewrite, inspect);
    }
}

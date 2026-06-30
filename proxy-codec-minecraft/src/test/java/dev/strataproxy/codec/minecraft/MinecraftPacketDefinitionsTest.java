package dev.strataproxy.codec.minecraft;

import dev.strataproxy.protocol.PacketDirection;
import dev.strataproxy.protocol.PacketFlag;
import dev.strataproxy.protocol.PacketView;
import dev.strataproxy.protocol.ProtocolState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftPacketDefinitionsTest {
    @Test
    void marksConfigurationCustomPayloadAsModdedInspectionPacket() {
        var registry = MinecraftPacketDefinitions.modernDefaults();
        var packet = new PacketView(PacketDirection.SERVERBOUND, ProtocolState.CONFIGURATION, 763, 0x01, 4096, 0, false);

        var definition = registry.find(packet).orElseThrow();

        assertTrue(definition.has(PacketFlag.REQUIRES_INSPECTION));
        assertTrue(definition.has(PacketFlag.SUSPICIOUS_WHEN_LARGE));
        assertTrue(definition.has(PacketFlag.MUST_PRESERVE_ORDER));
        assertTrue(definition.has(PacketFlag.MODDED_PAYLOAD));
    }
}

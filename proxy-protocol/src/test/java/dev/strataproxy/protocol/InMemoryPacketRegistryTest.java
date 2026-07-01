package dev.strataproxy.protocol;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InMemoryPacketRegistryTest {
    @Test
    void indexesByPacketIdentityAndKeepsProtocolVersionSelection() {
        var registry = new InMemoryPacketRegistry(List.of(
                definition(0x01, 0, 762, "legacy_custom_payload"),
                definition(0x01, 763, 999, "modern_custom_payload"),
                new PacketDefinition(
                        0x01,
                        ProtocolState.PLAY,
                        PacketDirection.SERVERBOUND,
                        0,
                        999,
                        EnumSet.noneOf(PacketFlag.class),
                        "play_packet")));

        var modern = registry.find(new PacketView(
                PacketDirection.SERVERBOUND,
                ProtocolState.CONFIGURATION,
                763,
                0x01,
                16,
                0,
                false));

        assertTrue(modern.isPresent());
        assertEquals("modern_custom_payload", modern.orElseThrow().name());
    }

    private static PacketDefinition definition(int id, int minProtocol, int maxProtocol, String name) {
        return new PacketDefinition(
                id,
                ProtocolState.CONFIGURATION,
                PacketDirection.SERVERBOUND,
                minProtocol,
                maxProtocol,
                EnumSet.noneOf(PacketFlag.class),
                name);
    }
}

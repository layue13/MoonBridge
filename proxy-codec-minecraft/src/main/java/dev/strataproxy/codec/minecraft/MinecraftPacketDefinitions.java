package dev.strataproxy.codec.minecraft;

import dev.strataproxy.protocol.InMemoryPacketRegistry;
import dev.strataproxy.protocol.PacketDefinition;
import dev.strataproxy.protocol.PacketDirection;
import dev.strataproxy.protocol.PacketFlag;
import dev.strataproxy.protocol.PacketRegistry;
import dev.strataproxy.protocol.ProtocolState;

import java.util.EnumSet;
import java.util.List;

/**
 * Built-in packet metadata definitions for modern Minecraft relay behavior.
 */
public final class MinecraftPacketDefinitions {
    private MinecraftPacketDefinitions() {
    }

    /**
 * Documents this public API element.
 *
     * @return default packet registry used by protocol-aware routing, inspection, and compression samples
     */
    public static PacketRegistry modernDefaults() {
        return new InMemoryPacketRegistry(List.of(
                new PacketDefinition(
                        0x00,
                        ProtocolState.HANDSHAKE,
                        PacketDirection.SERVERBOUND,
                        0,
                        Integer.MAX_VALUE,
                        EnumSet.of(PacketFlag.REQUIRES_INSPECTION, PacketFlag.MUST_PRESERVE_ORDER),
                        "handshake"),
                new PacketDefinition(
                        0x01,
                        ProtocolState.CONFIGURATION,
                        PacketDirection.SERVERBOUND,
                        0,
                        Integer.MAX_VALUE,
                        EnumSet.of(
                                PacketFlag.REQUIRES_INSPECTION,
                                PacketFlag.SUSPICIOUS_WHEN_LARGE,
                                PacketFlag.MUST_PRESERVE_ORDER,
                                PacketFlag.MODDED_PAYLOAD),
                        "custom_payload"),
                new PacketDefinition(
                        0x00,
                        ProtocolState.PLAY,
                        PacketDirection.CLIENTBOUND,
                        0,
                        Integer.MAX_VALUE,
                        EnumSet.of(PacketFlag.CAN_FAST_FORWARD, PacketFlag.CAN_COMPRESS, PacketFlag.HIGH_FREQUENCY),
                        "bundle_or_keepalive_fast_path_sample")));
    }
}

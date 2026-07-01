package dev.strataproxy.protocol;

import java.util.EnumSet;
import java.util.Objects;

/**
 * Static metadata for a Minecraft packet id in a protocol state and direction.
 *
 * @param id packet id in the selected protocol version
 * @param state protocol state where the packet appears
 * @param direction packet direction
 * @param minProtocol first Minecraft protocol version covered by this definition
 * @param maxProtocol last Minecraft protocol version covered by this definition
 * @param flags handling hints for relay, compression, and inspection
 * @param name human-readable packet name
 */
public record PacketDefinition(
        int id,
        ProtocolState state,
        PacketDirection direction,
        int minProtocol,
        int maxProtocol,
        EnumSet<PacketFlag> flags,
        String name) {
    /**
     * Validates and normalizes record components.
     */
    public PacketDefinition {
        state = Objects.requireNonNull(state, "state");
        direction = Objects.requireNonNull(direction, "direction");
        flags = flags == null ? EnumSet.noneOf(PacketFlag.class) : EnumSet.copyOf(flags);
        name = Objects.requireNonNull(name, "name");
        if (id < 0 || minProtocol < 0 || maxProtocol < minProtocol) {
            throw new IllegalArgumentException("invalid packet definition bounds");
        }
    }

    /**
 * Documents this public API element.
 *
     * @param protocolVersion Minecraft protocol version
     * @return {@code true} when this definition covers the version
     */
    public boolean appliesTo(int protocolVersion) {
        return protocolVersion >= minProtocol && protocolVersion <= maxProtocol;
    }

    /**
 * Documents this public API element.
 *
     * @param flag handling flag
     * @return {@code true} when the flag is set
     */
    public boolean has(PacketFlag flag) {
        return flags.contains(flag);
    }
}

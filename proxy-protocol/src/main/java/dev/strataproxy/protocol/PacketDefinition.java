package dev.strataproxy.protocol;

import java.util.EnumSet;
import java.util.Objects;

public record PacketDefinition(
        int id,
        ProtocolState state,
        PacketDirection direction,
        int minProtocol,
        int maxProtocol,
        EnumSet<PacketFlag> flags,
        String name) {
    public PacketDefinition {
        state = Objects.requireNonNull(state, "state");
        direction = Objects.requireNonNull(direction, "direction");
        flags = flags == null ? EnumSet.noneOf(PacketFlag.class) : EnumSet.copyOf(flags);
        name = Objects.requireNonNull(name, "name");
        if (id < 0 || minProtocol < 0 || maxProtocol < minProtocol) {
            throw new IllegalArgumentException("invalid packet definition bounds");
        }
    }

    public boolean appliesTo(int protocolVersion) {
        return protocolVersion >= minProtocol && protocolVersion <= maxProtocol;
    }

    public boolean has(PacketFlag flag) {
        return flags.contains(flag);
    }
}

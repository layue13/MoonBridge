package dev.strataproxy.api.server;

import java.util.Objects;

public record ProtocolRange(int minProtocol, int maxProtocol, String displayName) {
    public ProtocolRange {
        if (minProtocol < 0 || maxProtocol < 0) {
            throw new IllegalArgumentException("protocol versions must be non-negative");
        }
        if (minProtocol > maxProtocol) {
            throw new IllegalArgumentException("minProtocol must be <= maxProtocol");
        }
        displayName = Objects.requireNonNull(displayName, "displayName");
    }

    public boolean accepts(int protocolVersion) {
        return protocolVersion >= minProtocol && protocolVersion <= maxProtocol;
    }
}

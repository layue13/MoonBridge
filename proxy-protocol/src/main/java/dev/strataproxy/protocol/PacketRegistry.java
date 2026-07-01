package dev.strataproxy.protocol;

import java.util.Optional;

/**
 * Lookup service for protocol packet metadata.
 */
public interface PacketRegistry {
    /**
     * Finds the best metadata definition for a packet.
     *
     * @param packet observed packet
     * @return matching definition when known
     */
    Optional<PacketDefinition> find(PacketView packet);
}

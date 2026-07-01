package dev.strataproxy.protocol;

/**
 * Packet handling hints used by protocol-aware relay components.
 */
public enum PacketFlag {
    /** Packet can normally be forwarded without decoding its payload. */
    CAN_FAST_FORWARD,
    /** Packet contains entity references or other fields that may need rewriting. */
    REQUIRES_ENTITY_REWRITE,
    /** Packet should be decoded for policy or anomaly inspection. */
    REQUIRES_INSPECTION,
    /** Packet payload is eligible for compression decisions. */
    CAN_COMPRESS,
    /** Packet appears frequently enough that avoiding extra work matters. */
    HIGH_FREQUENCY,
    /** Packet size should be considered suspicious when unusually large. */
    SUSPICIOUS_WHEN_LARGE,
    /** Packet order must be preserved across relay operations. */
    MUST_PRESERVE_ORDER,
    /** Packet is associated with modded protocol payloads. */
    MODDED_PAYLOAD
}

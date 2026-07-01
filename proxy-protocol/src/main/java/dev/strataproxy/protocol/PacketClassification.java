package dev.strataproxy.protocol;

/**
 * Relay handling decision for a packet.
 *
 * @param packet packet being classified
 * @param definition matching metadata definition, or {@code null} when unknown
 * @param fastForward whether the packet can be copied without deep decoding
 * @param deepDecode whether the relay must decode packet contents
 * @param rewrite whether entity or protocol-specific fields must be rewritten
 * @param inspect whether policy code should inspect the packet
 */
public record PacketClassification(
        PacketView packet,
        PacketDefinition definition,
        boolean fastForward,
        boolean deepDecode,
        boolean rewrite,
        boolean inspect) {
}

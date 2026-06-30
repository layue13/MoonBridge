package dev.strataproxy.protocol;

public record PacketClassification(
        PacketView packet,
        PacketDefinition definition,
        boolean fastForward,
        boolean deepDecode,
        boolean rewrite,
        boolean inspect) {
}

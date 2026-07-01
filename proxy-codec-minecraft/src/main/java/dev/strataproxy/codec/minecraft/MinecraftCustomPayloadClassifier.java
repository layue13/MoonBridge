package dev.strataproxy.codec.minecraft;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Extracts channel and size metadata from Minecraft custom payload packets.
 */
public final class MinecraftCustomPayloadClassifier {
    private static final int MAX_CHANNEL_BYTES = 128;

    private MinecraftCustomPayloadClassifier() {
    }

    /**
     * Classifies a play-state custom payload frame.
     *
     * @param packetFrame packet payload containing packet id, channel, and custom payload bytes
     * @param largePayloadBytes threshold used to mark a payload as large; zero disables the flag
     * @return custom payload classification
     */
    public static CustomPayloadClassification classify(ByteBuf packetFrame, int largePayloadBytes) {
        if (packetFrame == null) {
            throw new IllegalArgumentException("packetFrame must not be null");
        }
        if (largePayloadBytes < 0) {
            throw new IllegalArgumentException("largePayloadBytes must be non-negative");
        }
        var view = packetFrame.retainedDuplicate();
        try {
            var packetId = MinecraftVarInts.read(view);
            var channel = readString(view, MAX_CHANNEL_BYTES);
            var payloadBytes = view.readableBytes();
            return new CustomPayloadClassification(
                    packetId,
                    channel,
                    payloadBytes,
                    classifyChannel(channel),
                    payloadBytes >= largePayloadBytes && largePayloadBytes > 0);
        } finally {
            view.release();
        }
    }

    /**
     * Classifies a login plugin request frame.
     *
     * @param packetFrame packet payload containing packet id, message id, channel, and payload bytes
     * @param largePayloadBytes threshold used to mark a payload as large; zero disables the flag
     * @return custom payload classification
     */
    public static CustomPayloadClassification classifyLoginPluginRequest(ByteBuf packetFrame, int largePayloadBytes) {
        if (packetFrame == null) {
            throw new IllegalArgumentException("packetFrame must not be null");
        }
        if (largePayloadBytes < 0) {
            throw new IllegalArgumentException("largePayloadBytes must be non-negative");
        }
        var view = packetFrame.retainedDuplicate();
        try {
            var packetId = MinecraftVarInts.read(view);
            MinecraftVarInts.read(view);
            var channel = readString(view, MAX_CHANNEL_BYTES);
            var payloadBytes = view.readableBytes();
            return new CustomPayloadClassification(
                    packetId,
                    channel,
                    payloadBytes,
                    classifyChannel(channel),
                    payloadBytes >= largePayloadBytes && largePayloadBytes > 0);
        } finally {
            view.release();
        }
    }

    private static String readString(ByteBuf input, int maxBytes) {
        var length = MinecraftVarInts.read(input);
        if (length < 0 || length > maxBytes) {
            throw new MinecraftCodecException("custom payload channel length exceeds maximum");
        }
        if (input.readableBytes() < length) {
            throw new MinecraftCodecException("truncated custom payload channel");
        }
        var bytes = new byte[length];
        input.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static CustomPayloadKind classifyChannel(String channel) {
        var normalized = channel.toLowerCase(Locale.ROOT);
        if (normalized.equals("minecraft:brand") || normalized.equals("brand")) {
            return CustomPayloadKind.BRAND;
        }
        if (normalized.startsWith("fml:")
                || normalized.startsWith("forge:")
                || normalized.equals("fml|hs")
                || normalized.equals("forge")) {
            return CustomPayloadKind.FORGE_HANDSHAKE;
        }
        if (normalized.startsWith("fabric:")
                || normalized.equals("fabric:registry/sync")
                || normalized.equals("fabric:modlist")) {
            return CustomPayloadKind.FABRIC_HANDSHAKE;
        }
        if (normalized.contains("registry") || normalized.contains("config")) {
            return CustomPayloadKind.REGISTRY_OR_CONFIG_SYNC;
        }
        return CustomPayloadKind.UNKNOWN;
    }

    /**
     * Coarse custom payload category.
     */
    public enum CustomPayloadKind {
        /** Client or backend brand payload. */
        BRAND,
        /** Forge/FML handshake payload. */
        FORGE_HANDSHAKE,
        /** Fabric handshake or registry payload. */
        FABRIC_HANDSHAKE,
        /** Registry or configuration synchronization payload. */
        REGISTRY_OR_CONFIG_SYNC,
        /** Channel did not match a known category. */
        UNKNOWN
    }

    /**
     * Metadata extracted from a custom payload packet.
     *
     * @param packetId packet id read from the frame
     * @param channel custom payload channel
     * @param payloadBytes remaining payload bytes after the channel field
     * @param kind coarse channel category
     * @param largePayload whether payload size meets the configured large-payload threshold
     */
    public record CustomPayloadClassification(
            int packetId,
            String channel,
            int payloadBytes,
            CustomPayloadKind kind,
            boolean largePayload) {
    }
}

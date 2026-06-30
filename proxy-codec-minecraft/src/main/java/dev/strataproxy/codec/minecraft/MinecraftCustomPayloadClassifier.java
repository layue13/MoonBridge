package dev.strataproxy.codec.minecraft;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class MinecraftCustomPayloadClassifier {
    private static final int MAX_CHANNEL_BYTES = 128;

    private MinecraftCustomPayloadClassifier() {
    }

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

    public enum CustomPayloadKind {
        BRAND,
        FORGE_HANDSHAKE,
        FABRIC_HANDSHAKE,
        REGISTRY_OR_CONFIG_SYNC,
        UNKNOWN
    }

    public record CustomPayloadClassification(
            int packetId,
            String channel,
            int payloadBytes,
            CustomPayloadKind kind,
            boolean largePayload) {
    }
}

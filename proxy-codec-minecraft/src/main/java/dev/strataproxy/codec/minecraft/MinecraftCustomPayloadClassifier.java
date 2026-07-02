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
        return classify(packetFrame, largePayloadBytes, PayloadLengthFormat.REMAINING_BYTES);
    }

    /**
     * Classifies a play-state custom payload frame.
     *
     * @param packetFrame packet payload containing packet id, channel, and custom payload bytes
     * @param largePayloadBytes threshold used to mark a payload as large; zero disables the flag
     * @param payloadLengthFormat custom payload body length encoding used by the protocol
     * @return custom payload classification
     */
    public static CustomPayloadClassification classify(
            ByteBuf packetFrame,
            int largePayloadBytes,
            PayloadLengthFormat payloadLengthFormat) {
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
            var payloadBytes = payloadLength(payloadLengthFormat, view);
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

    private static int payloadLength(PayloadLengthFormat format, ByteBuf input) {
        var resolved = format == null ? PayloadLengthFormat.REMAINING_BYTES : format;
        return switch (resolved) {
            case REMAINING_BYTES -> input.readableBytes();
            case UNSIGNED_SHORT -> unsignedShortPayloadLength(input);
            case VARSHORT -> varShortPayloadLength(input);
        };
    }

    private static int unsignedShortPayloadLength(ByteBuf input) {
        if (input.readableBytes() < Short.BYTES) {
            throw new MinecraftCodecException("truncated custom payload length");
        }
        var length = input.readUnsignedShort();
        if (input.readableBytes() < length) {
            throw new MinecraftCodecException("truncated custom payload body");
        }
        return length;
    }

    private static int varShortPayloadLength(ByteBuf input) {
        if (input.readableBytes() < Short.BYTES) {
            throw new MinecraftCodecException("truncated custom payload length");
        }
        var low = input.readUnsignedShort();
        var length = low & 0x7FFF;
        if ((low & 0x8000) != 0) {
            if (!input.isReadable()) {
                throw new MinecraftCodecException("truncated custom payload varshort extension");
            }
            length |= input.readUnsignedByte() << 15;
        }
        if (input.readableBytes() < length) {
            throw new MinecraftCodecException("truncated custom payload body");
        }
        return length;
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
                || normalized.equals("fml")
                || normalized.equals("fml|hs")
                || normalized.equals("fml|mp")
                || normalized.equals("register")
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
     * Custom payload body length encoding.
     */
    public enum PayloadLengthFormat {
        /** Payload consumes the rest of the packet after the channel field. */
        REMAINING_BYTES,
        /** Payload is prefixed by a two-byte unsigned length. */
        UNSIGNED_SHORT,
        /** Forge-compatible 1.7 clientbound extension over the two-byte length. */
        VARSHORT
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

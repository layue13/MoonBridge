package dev.strataproxy.infrastructure.minecraft;

import dev.strataproxy.infrastructure.minecraft.codec.MinecraftVarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class BungeeLegacyForwarding {
    private BungeeLegacyForwarding() {
    }

    static ByteBuf rewriteHandshake(
            ByteBufAllocator allocator,
            MinecraftHandshake handshake,
            MinecraftForwardingRuntime runtime,
            RelaySessionIdentity identity) {
        if (runtime == null || !runtime.bungeeHandshakeForwarding()) {
            throw new IllegalArgumentException("bungee handshake forwarding is not enabled");
        }
        if (runtime.bungeeGuard() && runtime.secret().isBlank()) {
            throw new IllegalArgumentException("bungee-guard forwarding requires a non-empty secret");
        }
        var profile = identity.profile();
        var username = profile == null || profile.name().isBlank() ? identity.playerName() : profile.name();
        var uuid = profile == null || profile.id() == null ? offlineUuid(username) : profile.id();
        var host = forwardedHost(
                handshake.requestedHost(),
                remoteAddress(identity.remoteAddress()),
                uuid,
                profile,
                runtime);

        var packet = allocator.buffer();
        try {
            MinecraftVarInts.write(packet, 0);
            MinecraftVarInts.write(packet, handshake.protocolVersion());
            writeString(packet, host);
            packet.writeShort(handshake.requestedPort());
            MinecraftVarInts.write(packet, handshake.nextState());

            var frame = allocator.buffer(MinecraftVarInts.encodedSize(packet.readableBytes()) + packet.readableBytes());
            MinecraftVarInts.write(frame, packet.readableBytes());
            frame.writeBytes(packet);
            return frame;
        } finally {
            packet.release();
        }
    }

    private static String forwardedHost(
            String requestedHost,
            String remoteAddress,
            UUID uuid,
            MinecraftSessionVerifier.GameProfile profile,
            MinecraftForwardingRuntime runtime) {
        var builder = new StringBuilder(requestedHost)
                .append('\0')
                .append(remoteAddress)
                .append('\0')
                .append(uuid.toString().replace("-", ""))
                .append('\0')
                .append(propertiesJson(profile));
        if (runtime.bungeeGuard()) {
            builder.append('\0').append(runtime.secret());
        }
        return builder.toString();
    }

    private static String propertiesJson(MinecraftSessionVerifier.GameProfile profile) {
        if (profile == null || profile.properties().isEmpty()) {
            return "[]";
        }
        var builder = new StringBuilder("[");
        var first = true;
        for (var property : profile.properties()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append('{')
                    .append("\"name\":\"").append(escapeJson(property.name())).append("\",")
                    .append("\"value\":\"").append(escapeJson(property.value())).append("\"");
            if (!property.signature().isBlank()) {
                builder.append(',').append("\"signature\":\"").append(escapeJson(property.signature())).append("\"");
            }
            builder.append('}');
        }
        return builder.append(']').toString();
    }

    private static String escapeJson(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        var builder = new StringBuilder(value.length() + 8);
        for (var index = 0; index < value.length(); index++) {
            var current = value.charAt(index);
            switch (current) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (current < 0x20) {
                        builder.append("\\u").append(String.format("%04x", (int) current));
                    } else {
                        builder.append(current);
                    }
                }
            }
        }
        return builder.toString();
    }

    private static UUID offlineUuid(String username) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + (username == null ? "" : username)).getBytes(StandardCharsets.UTF_8));
    }

    private static String remoteAddress(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isBlank()) {
            return "";
        }
        if (remoteAddress.startsWith("/")) {
            remoteAddress = remoteAddress.substring(1);
        }
        if (remoteAddress.startsWith("[") && remoteAddress.contains("]")) {
            return remoteAddress.substring(1, remoteAddress.indexOf(']'));
        }
        var colon = remoteAddress.lastIndexOf(':');
        if (colon > 0) {
            return remoteAddress.substring(0, colon);
        }
        return remoteAddress;
    }

    private static void writeString(ByteBuf output, String value) {
        var bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        MinecraftVarInts.write(output, bytes.length);
        output.writeBytes(bytes);
    }
}

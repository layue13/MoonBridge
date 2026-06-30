package dev.strataproxy.network;

import dev.strataproxy.codec.minecraft.MinecraftVarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class VelocityModernForwarding {
    static final String CHANNEL = "velocity:player_info";
    private static final int LOGIN_PLUGIN_REQUEST_PACKET_ID = 0x04;
    private static final int LOGIN_PLUGIN_RESPONSE_PACKET_ID = 0x02;
    private static final int MODERN_FORWARDING_DEFAULT = 1;
    private static final int MODERN_FORWARDING_WITH_KEY = 2;
    private static final int SIGNATURE_BYTES = 32;

    private VelocityModernForwarding() {
    }

    static ForwardingRequest request(ByteBuf frame, int maxFrameBytes) {
        var duplicate = frame.retainedDuplicate();
        try {
            var probe = MinecraftProtocolCodec.probeFrame(duplicate, maxFrameBytes);
            if (!probe.complete()) {
                return ForwardingRequest.none();
            }
            duplicate.skipBytes(probe.varIntBytes());
            var packetId = MinecraftProtocolCodec.readVarInt(duplicate);
            if (packetId != LOGIN_PLUGIN_REQUEST_PACKET_ID) {
                return ForwardingRequest.none();
            }
            var messageId = MinecraftProtocolCodec.readVarInt(duplicate);
            var channel = MinecraftProtocolCodec.readString(duplicate, 128);
            if (!CHANNEL.equals(channel)) {
                return ForwardingRequest.none();
            }
            var version = duplicate.isReadable() ? MinecraftProtocolCodec.readVarInt(duplicate) : MODERN_FORWARDING_DEFAULT;
            return new ForwardingRequest(true, messageId, version);
        } catch (RuntimeException exception) {
            return ForwardingRequest.none();
        } finally {
            duplicate.release();
        }
    }

    static ByteBuf response(
            ByteBufAllocator allocator,
            int messageId,
            int requestedVersion,
            MinecraftForwardingRuntime runtime,
            RelaySessionIdentity identity) {
        if (runtime == null || !runtime.velocityModern() || runtime.secret().isBlank()) {
            throw new IllegalArgumentException("velocity modern forwarding requires a non-empty secret");
        }
        var profile = identity.profile();
        var username = profile == null || profile.name().isBlank() ? identity.playerName() : profile.name();
        var uuid = profile == null || profile.id() == null ? offlineUuid(username) : profile.id();
        var chatSessionKey = identity.chatSessionKey();
        var responseVersion = requestedVersion >= MODERN_FORWARDING_WITH_KEY && chatSessionKey != null
                ? MODERN_FORWARDING_WITH_KEY
                : MODERN_FORWARDING_DEFAULT;
        var payload = Unpooled.buffer();
        try {
            payload.writeZero(SIGNATURE_BYTES);
            MinecraftVarInts.write(payload, responseVersion);
            writeString(payload, remoteAddress(identity.remoteAddress()));
            writeUuid(payload, uuid);
            writeString(payload, username);
            var properties = profile == null ? java.util.List.<MinecraftSessionVerifier.Property>of() : profile.properties();
            MinecraftVarInts.write(payload, properties.size());
            for (var property : properties) {
                writeString(payload, property.name());
                writeString(payload, property.value());
                payload.writeBoolean(!property.signature().isBlank());
                if (!property.signature().isBlank()) {
                    writeString(payload, property.signature());
                }
            }
            if (responseVersion == MODERN_FORWARDING_WITH_KEY) {
                payload.writeLong(chatSessionKey.expiresAtEpochMillis());
                writeByteArray(payload, chatSessionKey.encodedPublicKey());
                writeByteArray(payload, chatSessionKey.signature());
            }
            var signedBytes = new byte[payload.readableBytes() - SIGNATURE_BYTES];
            payload.getBytes(payload.readerIndex() + SIGNATURE_BYTES, signedBytes);
            payload.setBytes(payload.readerIndex(), signature(runtime.secret(), signedBytes));

            var packet = allocator.buffer();
            try {
                MinecraftVarInts.write(packet, LOGIN_PLUGIN_RESPONSE_PACKET_ID);
                MinecraftVarInts.write(packet, messageId);
                packet.writeBoolean(true);
                packet.writeBytes(payload, payload.readerIndex(), payload.readableBytes());

                var frame = allocator.buffer(MinecraftVarInts.encodedSize(packet.readableBytes()) + packet.readableBytes());
                MinecraftVarInts.write(frame, packet.readableBytes());
                frame.writeBytes(packet);
                return frame;
            } finally {
                packet.release();
            }
        } finally {
            payload.release();
        }
    }

    private static byte[] signature(String secret, byte[] payload) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("failed to sign velocity forwarding payload", exception);
        }
    }

    private static UUID offlineUuid(String username) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
    }

    private static String remoteAddress(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isBlank()) {
            return "";
        }
        if (remoteAddress.startsWith("/")) {
            remoteAddress = remoteAddress.substring(1);
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

    private static void writeByteArray(ByteBuf output, byte[] value) {
        MinecraftVarInts.write(output, value.length);
        output.writeBytes(value);
    }

    private static void writeUuid(ByteBuf output, UUID uuid) {
        output.writeLong(uuid.getMostSignificantBits());
        output.writeLong(uuid.getLeastSignificantBits());
    }

    record ForwardingRequest(boolean matched, int messageId, int version) {
        static ForwardingRequest none() {
            return new ForwardingRequest(false, -1, -1);
        }
    }
}

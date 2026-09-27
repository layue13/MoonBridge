package dev.moonbridge.core.forwarding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.moonbridge.core.auth.ProfileProperty;
import dev.moonbridge.core.auth.VerifiedProfile;
import dev.moonbridge.core.protocol.MinecraftHandshake;
import dev.moonbridge.core.protocol.ProtocolProfile;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.net.InetSocketAddress;
import java.util.Objects;

/** Encodes a verified player identity for a trusted backend using legacy Bungee forwarding. */
public final class BungeeLegacyForwarding {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ProtocolProfile BACKEND_PROFILE = new ProtocolProfile(
            ProtocolProfile.PROTOCOL_1_7_10, ProtocolProfile.minecraft1710().maxFrameBytes(), 32767, 16);

    private BungeeLegacyForwarding() { }

    public static ByteBuf encode(ByteBufAllocator allocator, MinecraftHandshake original,
                                 InetSocketAddress clientAddress, VerifiedProfile profile) {
        return encode(allocator, original, clientAddress, profile, null);
    }

    /** Adds an optional host-authenticated proxy-session proof to the forwarded profile properties. */
    public static ByteBuf encode(ByteBufAllocator allocator, MinecraftHandshake original,
                                 InetSocketAddress clientAddress, VerifiedProfile profile, String sessionProof) {
        Objects.requireNonNull(allocator, "allocator");
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(clientAddress, "clientAddress");
        Objects.requireNonNull(profile, "profile");
        if (original.nextState() != MinecraftHandshake.NextState.LOGIN
                || original.protocolVersion() != ProtocolProfile.PROTOCOL_1_7_10) {
            throw new IllegalArgumentException("legacy forwarding requires a protocol 5 login handshake");
        }
        if (clientAddress.getAddress() == null) {
            throw new IllegalArgumentException("client IP address must be resolved");
        }

        // Never forward client-supplied NUL segments. They could impersonate identity fields.
        int separator = original.serverAddress().indexOf('\0');
        String requestedHost = separator < 0 ? original.serverAddress()
                : original.serverAddress().substring(0, separator);
        if (requestedHost.isBlank()) throw new IllegalArgumentException("requested host is blank");
        StringBuilder host = new StringBuilder(requestedHost)
                .append('\0').append(clientAddress.getAddress().getHostAddress())
                .append('\0').append(profile.uuid().toString().replace("-", ""));
        if (!profile.properties().isEmpty() || sessionProof != null) {
            host.append('\0').append(propertiesJson(profile, sessionProof));
        }
        if (host.length() > BACKEND_PROFILE.maxHandshakeHostCharacters()) {
            throw new IllegalArgumentException("forwarded handshake host is too long");
        }
        return new MinecraftHandshake(original.protocolVersion(), host.toString(), original.serverPort(),
                MinecraftHandshake.NextState.LOGIN).encode(allocator, BACKEND_PROFILE);
    }

    private static String propertiesJson(VerifiedProfile profile, String sessionProof) {
        ArrayNode properties = JSON.createArrayNode();
        for (ProfileProperty property : profile.properties()) {
            ObjectNode entry = properties.addObject();
            entry.put("name", property.name());
            entry.put("value", property.value());
            if (property.signature() != null) entry.put("signature", property.signature());
        }
        if (sessionProof != null) {
            if (sessionProof.isBlank() || sessionProof.length() > 2048) {
                throw new IllegalArgumentException("session proof is invalid or too long");
            }
            ObjectNode entry = properties.addObject();
            entry.put("name", dev.moonbridge.messaging.session.ForwardedSessionProof.PROPERTY_NAME);
            entry.put("value", sessionProof);
        }
        try {
            return JSON.writeValueAsString(properties);
        } catch (JsonProcessingException impossibleForJsonTree) {
            throw new IllegalStateException("could not encode verified profile properties", impossibleForJsonTree);
        }
    }
}

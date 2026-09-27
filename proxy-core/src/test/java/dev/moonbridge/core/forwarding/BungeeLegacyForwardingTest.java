package dev.moonbridge.core.forwarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.moonbridge.core.auth.ProfileProperty;
import dev.moonbridge.core.auth.VerifiedProfile;
import dev.moonbridge.core.protocol.MinecraftHandshake;
import dev.moonbridge.core.protocol.ProtocolProfile;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BungeeLegacyForwardingTest {
    private static final ProtocolProfile BACKEND_PROFILE = new ProtocolProfile(5, 2 * 1024 * 1024, 32767, 16);

    @Test
    void forwardsVerifiedIdentityAndEscapedPropertiesWithoutClientSuppliedSegments() throws Exception {
        UUID uuid = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        VerifiedProfile profile = new VerifiedProfile(uuid, "Alice",
                List.of(new ProfileProperty("textures", "a\"b\\c", "signature")));
        MinecraftHandshake original = new MinecraftHandshake(5, "play.example\0spoofed-ip\0spoofed-uuid", 25565,
                MinecraftHandshake.NextState.LOGIN);

        ByteBuf encoded = BungeeLegacyForwarding.encode(UnpooledByteBufAllocator.DEFAULT, original,
                new InetSocketAddress("127.0.0.2", 12345), profile);
        try {
            MinecraftHandshake forwarded = MinecraftHandshake.decode(encoded, BACKEND_PROFILE);
            String[] fields = forwarded.serverAddress().split("\u0000", -1);
            assertEquals(4, fields.length);
            assertEquals("play.example", fields[0]);
            assertEquals("127.0.0.2", fields[1]);
            assertEquals("12345678123412341234123456789abc", fields[2]);
            var properties = new ObjectMapper().readTree(fields[3]);
            assertEquals("a\"b\\c", properties.get(0).get("value").textValue());
            assertEquals("signature", properties.get(0).get("signature").textValue());
        } finally {
            encoded.release();
        }
    }

    @Test
    void omitsPropertySegmentWhenProfileHasNoProperties() {
        MinecraftHandshake original = new MinecraftHandshake(5, "play.example", 25565,
                MinecraftHandshake.NextState.LOGIN);
        VerifiedProfile profile = new VerifiedProfile(UUID.fromString("12345678-1234-1234-1234-123456789abc"),
                "Alice", List.of());
        ByteBuf encoded = BungeeLegacyForwarding.encode(UnpooledByteBufAllocator.DEFAULT, original,
                new InetSocketAddress("127.0.0.2", 12345), profile);
        try {
            String[] fields = MinecraftHandshake.decode(encoded, BACKEND_PROFILE).serverAddress().split("\u0000", -1);
            assertEquals(3, fields.length);
        } finally {
            encoded.release();
        }
    }

    @Test
    void rejectsUnresolvedClientAddress() {
        MinecraftHandshake original = new MinecraftHandshake(5, "play.example", 25565,
                MinecraftHandshake.NextState.LOGIN);
        VerifiedProfile profile = new VerifiedProfile(UUID.randomUUID(), "Alice", List.of());
        assertThrows(IllegalArgumentException.class, () -> BungeeLegacyForwarding.encode(
                UnpooledByteBufAllocator.DEFAULT, original,
                InetSocketAddress.createUnresolved("unresolved.example", 12345), profile));
    }
}

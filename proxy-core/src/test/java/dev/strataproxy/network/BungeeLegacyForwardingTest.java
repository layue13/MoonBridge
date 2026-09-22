package dev.strataproxy.network;

import dev.strataproxy.network.MinecraftVarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BungeeLegacyForwardingTest {
    @Test
    void rewritesHandshakeWithBungeeLegacyForwardingFields() {
        var identity = new RelaySessionIdentity("/198.51.100.42:51234");
        identity.profile(new MinecraftSessionVerifier.GameProfile(
                UUID.fromString("12345678-1234-5678-1234-567812345678"),
                "PlayerOne",
                List.of(new MinecraftSessionVerifier.Property("textures", "value", "signature"))));

        var frame = BungeeLegacyForwarding.rewriteHandshake(
                UnpooledByteBufAllocator.DEFAULT,
                new MinecraftHandshake(763, "play.example.net", 25565, 2),
                new MinecraftForwardingRuntime("bungee-legacy", ""),
                identity);
        try {
            var body = unwrapFrame(frame);
            try {
                assertEquals(0, MinecraftVarInts.read(body));
                assertEquals(763, MinecraftVarInts.read(body));
                var host = readString(body);
                assertEquals(25565, body.readUnsignedShort());
                assertEquals(2, MinecraftVarInts.read(body));

                var fields = host.split("\0", -1);
                assertEquals(4, fields.length);
                assertEquals("play.example.net", fields[0]);
                assertEquals("198.51.100.42", fields[1]);
                assertEquals("12345678123456781234567812345678", fields[2]);
                assertEquals("[{\"name\":\"textures\",\"value\":\"value\",\"signature\":\"signature\"}]", fields[3]);
            } finally {
                body.release();
            }
        } finally {
            frame.release();
        }
    }

    @Test
    void appendsBungeeGuardSecret() {
        var identity = new RelaySessionIdentity("/203.0.113.7:51234");
        identity.playerName("OfflineName");

        var frame = BungeeLegacyForwarding.rewriteHandshake(
                UnpooledByteBufAllocator.DEFAULT,
                new MinecraftHandshake(763, "modded.example.net", 25565, 2),
                new MinecraftForwardingRuntime("bungee-guard", "shared-secret"),
                identity);
        try {
            var body = unwrapFrame(frame);
            try {
                assertEquals(0, MinecraftVarInts.read(body));
                assertEquals(763, MinecraftVarInts.read(body));
                var fields = readString(body).split("\0", -1);
                assertEquals(5, fields.length);
                assertEquals("modded.example.net", fields[0]);
                assertEquals("203.0.113.7", fields[1]);
                assertEquals("shared-secret", fields[4]);
            } finally {
                body.release();
            }
        } finally {
            frame.release();
        }
    }

    @Test
    void preservesLegacyForgeHostnameTokenWhenForwardingPlayerInfo() {
        var identity = new RelaySessionIdentity("/198.51.100.43:51234");
        identity.playerName("ForgePlayer");

        var frame = BungeeLegacyForwarding.rewriteHandshake(
                UnpooledByteBufAllocator.DEFAULT,
                new MinecraftHandshake(5, "modded.example.net\0FML\0", 25565, 2),
                new MinecraftForwardingRuntime("bungee-legacy", ""),
                identity);
        try {
            var body = unwrapFrame(frame);
            try {
                assertEquals(0, MinecraftVarInts.read(body));
                assertEquals(5, MinecraftVarInts.read(body));
                var host = readString(body);
                var forwardedUuid = UUID.nameUUIDFromBytes("OfflinePlayer:ForgePlayer".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .toString()
                        .replace("-", "");
                assertTrue(host.contains("\0FML\0"));
                assertTrue(host.endsWith("\0" + "198.51.100.43" + "\0" + forwardedUuid + "\0[]"));
                assertEquals(25565, body.readUnsignedShort());
                assertEquals(2, MinecraftVarInts.read(body));
            } finally {
                body.release();
            }
        } finally {
            frame.release();
        }
    }

    private static ByteBuf unwrapFrame(ByteBuf frame) {
        var duplicate = frame.retainedDuplicate();
        try {
            var length = MinecraftVarInts.read(duplicate);
            return duplicate.readRetainedSlice(length);
        } finally {
            duplicate.release();
        }
    }

    private static String readString(ByteBuf input) {
        var length = MinecraftVarInts.read(input);
        var bytes = new byte[length];
        input.readBytes(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}

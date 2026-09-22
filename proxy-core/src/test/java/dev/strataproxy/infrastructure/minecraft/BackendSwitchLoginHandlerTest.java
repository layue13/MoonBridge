package dev.strataproxy.infrastructure.minecraft;

import dev.strataproxy.infrastructure.minecraft.codec.MinecraftVarInts;
import dev.strataproxy.infrastructure.observability.ProxyMetrics;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

final class BackendSwitchLoginHandlerTest {
    @Test
    void legacySwitchReturnsJoinGameAsRemainingImmediatelyAfterLoginSuccess() {
        var clientbound = new AtomicReference<ByteBuf>();
        var remaining = new AtomicReference<ByteBuf>();
        var channel = new EmbeddedChannel(new BackendSwitchLoginHandler(
                new ProxyMetrics(),
                "lobby-1",
                4096,
                new MinecraftCompressionAuditState(4096),
                MinecraftForwardingRuntime.none(),
                new RelaySessionIdentity("127.0.0.1:50000"),
                MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10),
                new BackendSwitchLoginHandler.Listener() {
                    @Override
                    public void backendLoginReady(Channel backend, ByteBuf clientboundFrames, ByteBuf remainingBackendFrames) {
                        clientbound.set(clientboundFrames);
                        remaining.set(remainingBackendFrames);
                    }

                    @Override
                    public void backendLoginFailed(Channel backend, String outcome) {
                    }
                }));

        channel.writeInbound(combine(
                frame(packet(0x02)),
                joinGameFrame(12, 0, 0, 2, "default"),
                frame(packet(0x08))));

        var clientboundFrames = clientbound.get();
        var remainingBackendFrames = remaining.get();
        try {
            assertNull(clientboundFrames);

            assertNotNull(remainingBackendFrames);
            var joinGame = payload(remainingBackendFrames);
            try {
                assertEquals(0x01, MinecraftVarInts.read(joinGame));
            } finally {
                joinGame.release();
            }
            var payload = payload(remainingBackendFrames);
            try {
                assertEquals(0x08, MinecraftVarInts.read(payload));
            } finally {
                payload.release();
            }
            assertEquals(0, remainingBackendFrames.readableBytes());
        } finally {
            release(clientboundFrames);
            release(remainingBackendFrames);
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void modernSwitchReturnsRemainingBackendFramesWithoutClientReset() {
        var clientbound = new AtomicReference<ByteBuf>();
        var remaining = new AtomicReference<ByteBuf>();
        var channel = new EmbeddedChannel(new BackendSwitchLoginHandler(
                new ProxyMetrics(),
                "survival-1",
                4096,
                new MinecraftCompressionAuditState(4096),
                MinecraftForwardingRuntime.none(),
                new RelaySessionIdentity("127.0.0.1:50000"),
                MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_20_1),
                new BackendSwitchLoginHandler.Listener() {
                    @Override
                    public void backendLoginReady(Channel backend, ByteBuf clientboundFrames, ByteBuf remainingBackendFrames) {
                        clientbound.set(clientboundFrames);
                        remaining.set(remainingBackendFrames);
                    }

                    @Override
                    public void backendLoginFailed(Channel backend, String outcome) {
                    }
                }));

        channel.writeInbound(combine(frame(packet(0x02)), frame(packet(0x29))));

        try {
            assertNull(clientbound.get());
            var remainingBackendFrames = remaining.get();
            assertNotNull(remainingBackendFrames);
            var payload = payload(remainingBackendFrames);
            try {
                assertEquals(0x29, MinecraftVarInts.read(payload));
            } finally {
                payload.release();
            }
        } finally {
            release(remaining.get());
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void legacyForgeSwitchReturnsPreJoinRegisterImmediatelyAfterLoginSuccess() {
        var clientbound = new AtomicReference<ByteBuf>();
        var remaining = new AtomicReference<ByteBuf>();
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var tracker = new MinecraftForgeHandshakeTracker(4096, profile, true);
        var channel = new EmbeddedChannel(new BackendSwitchLoginHandler(
                new ProxyMetrics(),
                "forge-1",
                4096,
                new MinecraftCompressionAuditState(4096),
                MinecraftForwardingRuntime.none(),
                new RelaySessionIdentity("127.0.0.1:50000"),
                profile,
                tracker,
                new BackendSwitchLoginHandler.Listener() {
                    @Override
                    public void backendLoginReady(Channel backend, ByteBuf clientboundFrames, ByteBuf remainingBackendFrames) {
                        clientbound.set(clientboundFrames);
                        remaining.set(remainingBackendFrames);
                    }

                    @Override
                    public void backendLoginFailed(Channel backend, String outcome) {
                    }
                }));

        channel.writeInbound(combine(
                frame(packet(0x02)),
                clientboundCustomPayloadFrame("REGISTER", registerPayload()),
                joinGameFrame(12, 0, 0, 2, "default")));

        var clientboundFrames = clientbound.get();
        var remainingBackendFrames = remaining.get();
        try {
            assertNull(clientboundFrames);
            assertNotNull(remainingBackendFrames);
            assertRegister(remainingBackendFrames);
            var joinGame = payload(remainingBackendFrames);
            try {
                assertEquals(0x01, MinecraftVarInts.read(joinGame));
            } finally {
                joinGame.release();
            }
        } finally {
            release(clientboundFrames);
            release(remainingBackendFrames);
            channel.finishAndReleaseAll();
            tracker.close();
        }
    }

    @Test
    void legacySwitchDoesNotBufferPreJoinGameFramesAgainstPendingLimit() {
        var failure = new AtomicReference<String>();
        var remaining = new AtomicReference<ByteBuf>();
        var channel = new EmbeddedChannel(new BackendSwitchLoginHandler(
                new ProxyMetrics(),
                "lobby-1",
                8,
                new MinecraftCompressionAuditState(8),
                MinecraftForwardingRuntime.none(),
                new RelaySessionIdentity("127.0.0.1:50000"),
                MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10),
                new BackendSwitchLoginHandler.Listener() {
                    @Override
                    public void backendLoginReady(Channel backend, ByteBuf clientboundFrames, ByteBuf remainingBackendFrames) {
                        release(clientboundFrames);
                        remaining.set(remainingBackendFrames);
                    }

                    @Override
                    public void backendLoginFailed(Channel backend, String outcome) {
                        failure.set(outcome);
                    }
                }));

        channel.writeInbound(combine(
                frame(packet(0x02)),
                manyFrames(0x08, 40)));

        assertNull(failure.get());
        assertNotNull(remaining.get());
        release(remaining.get());
        channel.finishAndReleaseAll();
    }

    @Test
    void backendRelayConvertsFirstLegacySwitchJoinGameToRespawns() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var frontend = new EmbeddedChannel();
        var backend = new EmbeddedChannel(new BackendRelayHandler(
                frontend,
                new ProxyMetrics(),
                "lobby-1",
                4096,
                new MinecraftCompressionAuditState(4096),
                CompressionRuntime.defaults(),
                new RelaySessionIdentity("127.0.0.1:50000"),
                MinecraftForwardingRuntime.none(),
                false,
                25,
                profile,
                null,
                null,
                true));

        backend.writeInbound(combine(
                joinGameFrame(12, 0, 0, 2, "default"),
                frame(packet(0x08))));

        var frames = (ByteBuf) frontend.readOutbound();
        try {
            assertRespawn(frames, -1, 2, 0, "default");
            assertRespawn(frames, 0, 2, 0, "default");
            var payload = payload(frames);
            try {
                assertEquals(0x08, MinecraftVarInts.read(payload));
            } finally {
                payload.release();
            }
            assertEquals(0, frames.readableBytes());
        } finally {
            release(frames);
            backend.finishAndReleaseAll();
            frontend.finishAndReleaseAll();
        }
    }

    @Test
    void backendRelayKeepsJoinGameForLegacyForgeSafeSwitch() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var frontend = new EmbeddedChannel();
        var backend = new EmbeddedChannel(new BackendRelayHandler(
                frontend,
                new ProxyMetrics(),
                "forge-1",
                4096,
                new MinecraftCompressionAuditState(4096),
                CompressionRuntime.defaults(),
                new RelaySessionIdentity("127.0.0.1:50000"),
                MinecraftForwardingRuntime.none(),
                false,
                25,
                profile,
                null,
                null,
                true,
                true));

        backend.writeInbound(combine(
                joinGameForge1710Frame(12, 0, 0, 2, "default"),
                frame(packet(0x08))));

        var frames = (ByteBuf) frontend.readOutbound();
        try {
            assertJoinGameForge1710(frames, 12, 0, 0, 2, "default");
            assertRespawn(frames, -1, 2, 0, "default");
            assertRespawn(frames, 0, 2, 0, "default");
            var payload = payload(frames);
            try {
                assertEquals(0x08, MinecraftVarInts.read(payload));
            } finally {
                payload.release();
            }
            assertEquals(0, frames.readableBytes());
        } finally {
            release(frames);
            backend.finishAndReleaseAll();
            frontend.finishAndReleaseAll();
        }
    }

    @Test
    void backendRelayParsesForge1710JoinGameWithIntDimensionDuringSafeSwitch() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var frontend = new EmbeddedChannel();
        var backend = new EmbeddedChannel(new BackendRelayHandler(
                frontend,
                new ProxyMetrics(),
                "forge-1",
                4096,
                new MinecraftCompressionAuditState(4096),
                CompressionRuntime.defaults(),
                new RelaySessionIdentity("127.0.0.1:50000"),
                MinecraftForwardingRuntime.none(),
                false,
                25,
                profile,
                null,
                null,
                profile.backendSwitchStrategy(true)));

        backend.writeInbound(joinGameForge1710Frame(12, 0, 300, 2, "default"));

        var frames = (ByteBuf) frontend.readOutbound();
        try {
            assertJoinGameForge1710(frames, 12, 0, 300, 2, "default");
            assertRespawn(frames, -1, 2, 0, "default");
            assertRespawn(frames, 300, 2, 0, "default");
            assertEquals(0, frames.readableBytes());
        } finally {
            release(frames);
            backend.finishAndReleaseAll();
            frontend.finishAndReleaseAll();
        }
    }

    @Test
    void backendRelayUsesLegacyForgeSafeSwitchFor18JoinGame() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_8);
        var frontend = new EmbeddedChannel();
        var backend = new EmbeddedChannel(new BackendRelayHandler(
                frontend,
                new ProxyMetrics(),
                "forge-1",
                4096,
                new MinecraftCompressionAuditState(4096),
                CompressionRuntime.defaults(),
                new RelaySessionIdentity("127.0.0.1:50000"),
                MinecraftForwardingRuntime.none(),
                false,
                25,
                profile,
                null,
                null,
                profile.backendSwitchStrategy(true)));

        backend.writeInbound(combine(
                joinGame18Frame(12, 0, 0, 2, "default", true),
                frame(packet(0x08))));

        var frames = (ByteBuf) frontend.readOutbound();
        try {
            assertJoinGame18(frames, 12, 0, 0, 2, "default", true);
            assertRespawn(frames, -1, 2, 0, "default");
            assertRespawn(frames, 0, 2, 0, "default");
            var payload = payload(frames);
            try {
                assertEquals(0x08, MinecraftVarInts.read(payload));
            } finally {
                payload.release();
            }
            assertEquals(0, frames.readableBytes());
        } finally {
            release(frames);
            backend.finishAndReleaseAll();
            frontend.finishAndReleaseAll();
        }
    }

    @Test
    void backendRelayBuffersSplitLegacySwitchJoinGameUntilComplete() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var frontend = new EmbeddedChannel();
        var backend = new EmbeddedChannel(new BackendRelayHandler(
                frontend,
                new ProxyMetrics(),
                "lobby-1",
                4096,
                new MinecraftCompressionAuditState(4096),
                CompressionRuntime.defaults(),
                new RelaySessionIdentity("127.0.0.1:50000"),
                MinecraftForwardingRuntime.none(),
                false,
                25,
                profile,
                null,
                null,
                true));
        var joinGame = joinGameFrame(12, 0, 0, 2, "default");
        var firstHalf = joinGame.readRetainedSlice(joinGame.readableBytes() / 2);
        var secondHalf = joinGame.readRetainedSlice(joinGame.readableBytes());
        joinGame.release();

        assertFalse(backend.writeInbound(firstHalf));
        assertNull(frontend.readOutbound());

        backend.writeInbound(secondHalf);

        var frames = (ByteBuf) frontend.readOutbound();
        try {
            assertRespawn(frames, -1, 2, 0, "default");
            assertRespawn(frames, 0, 2, 0, "default");
            assertEquals(0, frames.readableBytes());
        } finally {
            release(frames);
            backend.finishAndReleaseAll();
            frontend.finishAndReleaseAll();
        }
    }

    @Test
    void backendRelayClosesOnOversizedLegacySwitchJoinGame() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var frontend = new EmbeddedChannel();
        var backend = new EmbeddedChannel(new BackendRelayHandler(
                frontend,
                new ProxyMetrics(),
                "lobby-1",
                8,
                new MinecraftCompressionAuditState(8),
                CompressionRuntime.defaults(),
                new RelaySessionIdentity("127.0.0.1:50000"),
                MinecraftForwardingRuntime.none(),
                false,
                25,
                profile,
                null,
                null,
                true));
        var joinGame = joinGameFrame(12, 0, 0, 2, "default");
        var first = joinGame.readRetainedSlice(6);
        var second = joinGame.readRetainedSlice(joinGame.readableBytes());
        joinGame.release();

        try {
            assertFalse(backend.writeInbound(first));
            assertNull(frontend.readOutbound());

            assertFalse(backend.isOpen());
            assertFalse(frontend.isOpen());
            assertNull(frontend.readOutbound());
        } finally {
            release(second);
            backend.finishAndReleaseAll();
            frontend.finishAndReleaseAll();
        }
    }

    @Test
    void backendRelayClosesOnMalformedLegacySwitchJoinGame() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var frontend = new EmbeddedChannel();
        var backend = new EmbeddedChannel(new BackendRelayHandler(
                frontend,
                new ProxyMetrics(),
                "lobby-1",
                4096,
                new MinecraftCompressionAuditState(4096),
                CompressionRuntime.defaults(),
                new RelaySessionIdentity("127.0.0.1:50000"),
                MinecraftForwardingRuntime.none(),
                false,
                25,
                profile,
                null,
                null,
                true));

        try {
            assertFalse(backend.writeInbound(frame(packet(0x01))));

            assertFalse(backend.isOpen());
            assertFalse(frontend.isOpen());
            assertNull(frontend.readOutbound());
        } finally {
            backend.finishAndReleaseAll();
            frontend.finishAndReleaseAll();
        }
    }

    private static ByteBuf joinGameFrame(int entityId, int gameMode, int dimension, int difficulty, String levelType) {
        var packet = Unpooled.buffer();
        MinecraftVarInts.write(packet, 0x01);
        packet.writeInt(entityId);
        packet.writeByte(gameMode);
        packet.writeByte(dimension);
        packet.writeByte(difficulty);
        packet.writeByte(20);
        writeString(packet, levelType);
        return frame(packet);
    }

    private static ByteBuf joinGameForge1710Frame(
            int entityId,
            int gameMode,
            int dimension,
            int difficulty,
            String levelType) {
        var packet = Unpooled.buffer();
        MinecraftVarInts.write(packet, 0x01);
        packet.writeInt(entityId);
        packet.writeByte(gameMode);
        packet.writeInt(dimension);
        packet.writeByte(difficulty);
        packet.writeByte(20);
        writeString(packet, levelType);
        return frame(packet);
    }

    private static ByteBuf joinGame18Frame(
            int entityId,
            int gameMode,
            int dimension,
            int difficulty,
            String levelType,
            boolean reducedDebugInfo) {
        var packet = Unpooled.buffer();
        MinecraftVarInts.write(packet, 0x01);
        packet.writeInt(entityId);
        packet.writeByte(gameMode);
        packet.writeByte(dimension);
        packet.writeByte(difficulty);
        packet.writeByte(20);
        writeString(packet, levelType);
        packet.writeBoolean(reducedDebugInfo);
        return frame(packet);
    }

    private static ByteBuf clientboundCustomPayloadFrame(String channel, ByteBuf payload) {
        var packet = Unpooled.buffer();
        try {
            MinecraftVarInts.write(packet, 0x3F);
            writeString(packet, channel);
            writeVarShort(packet, payload.readableBytes());
            packet.writeBytes(payload, payload.readerIndex(), payload.readableBytes());
            return frame(packet);
        } finally {
            payload.release();
        }
    }

    private static ByteBuf registerPayload() {
        return Unpooled.wrappedBuffer("FML|HS\0FML\0FML|MP\0FORGE".getBytes(StandardCharsets.UTF_8));
    }

    private static ByteBuf frame(ByteBuf packet) {
        var frame = Unpooled.buffer();
        MinecraftVarInts.write(frame, packet.readableBytes());
        frame.writeBytes(packet, packet.readerIndex(), packet.readableBytes());
        packet.release();
        return frame;
    }

    private static ByteBuf packet(int packetId) {
        var packet = Unpooled.buffer();
        MinecraftVarInts.write(packet, packetId);
        return packet;
    }

    private static ByteBuf combine(ByteBuf... frames) {
        var combined = Unpooled.buffer();
        for (var frame : frames) {
            try {
                combined.writeBytes(frame, frame.readerIndex(), frame.readableBytes());
            } finally {
                frame.release();
            }
        }
        return combined;
    }

    private static ByteBuf manyFrames(int packetId, int count) {
        var combined = Unpooled.buffer();
        for (var index = 0; index < count; index++) {
            var frame = frame(packet(packetId));
            try {
                combined.writeBytes(frame, frame.readerIndex(), frame.readableBytes());
            } finally {
                frame.release();
            }
        }
        return combined;
    }

    private static void assertForgeReset(ByteBuf frames) {
        var payload = payload(frames);
        try {
            assertEquals(0x3F, MinecraftVarInts.read(payload));
            assertEquals("FML|HS", readString(payload));
            assertEquals(2, readVarShort(payload));
            assertEquals(0xFE, payload.readUnsignedByte());
            assertEquals(0, payload.readUnsignedByte());
            assertEquals(0, payload.readableBytes());
        } finally {
            payload.release();
        }
    }

    private static void assertRegister(ByteBuf frames) {
        var payload = payload(frames);
        try {
            assertEquals(0x3F, MinecraftVarInts.read(payload));
            assertEquals("REGISTER", readString(payload));
            var length = readVarShort(payload);
            assertEquals("FML|HS\0FML\0FML|MP\0FORGE",
                    payload.toString(payload.readerIndex(), length, StandardCharsets.UTF_8));
        } finally {
            payload.release();
        }
    }

    private static ByteBuf payload(ByteBuf frames) {
        var length = MinecraftVarInts.read(frames);
        return frames.readRetainedSlice(length);
    }

    private static void assertJoinGame(
            ByteBuf frames,
            int entityId,
            int gameMode,
            int dimension,
            int difficulty,
            String levelType) {
        var payload = payload(frames);
        try {
            assertEquals(0x01, MinecraftVarInts.read(payload));
            assertEquals(entityId, payload.readInt());
            assertEquals(gameMode, payload.readUnsignedByte());
            assertEquals(dimension, payload.readByte());
            assertEquals(difficulty, payload.readUnsignedByte());
            payload.readUnsignedByte();
            assertEquals(levelType, readString(payload));
            assertEquals(0, payload.readableBytes());
        } finally {
            payload.release();
        }
    }

    private static void assertJoinGameForge1710(
            ByteBuf frames,
            int entityId,
            int gameMode,
            int dimension,
            int difficulty,
            String levelType) {
        var payload = payload(frames);
        try {
            assertEquals(0x01, MinecraftVarInts.read(payload));
            assertEquals(entityId, payload.readInt());
            assertEquals(gameMode, payload.readUnsignedByte());
            assertEquals(dimension, payload.readInt());
            assertEquals(difficulty, payload.readUnsignedByte());
            payload.readUnsignedByte();
            assertEquals(levelType, readString(payload));
            assertEquals(0, payload.readableBytes());
        } finally {
            payload.release();
        }
    }

    private static void assertJoinGame18(
            ByteBuf frames,
            int entityId,
            int gameMode,
            int dimension,
            int difficulty,
            String levelType,
            boolean reducedDebugInfo) {
        var payload = payload(frames);
        try {
            assertEquals(0x01, MinecraftVarInts.read(payload));
            assertEquals(entityId, payload.readInt());
            assertEquals(gameMode, payload.readUnsignedByte());
            assertEquals(dimension, payload.readByte());
            assertEquals(difficulty, payload.readUnsignedByte());
            payload.readUnsignedByte();
            assertEquals(levelType, readString(payload));
            assertEquals(reducedDebugInfo, payload.readBoolean());
            assertEquals(0, payload.readableBytes());
        } finally {
            payload.release();
        }
    }

    private static void assertRespawn(ByteBuf frames, int dimension, int difficulty, int gameMode, String levelType) {
        var payload = payload(frames);
        try {
            assertEquals(0x07, MinecraftVarInts.read(payload));
            assertEquals(dimension, payload.readInt());
            assertEquals(difficulty, payload.readUnsignedByte());
            assertEquals(gameMode, payload.readUnsignedByte());
            assertEquals(levelType, readString(payload));
            assertEquals(0, payload.readableBytes());
        } finally {
            payload.release();
        }
    }

    private static void writeString(ByteBuf output, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        MinecraftVarInts.write(output, bytes.length);
        output.writeBytes(bytes);
    }

    private static void writeVarShort(ByteBuf output, int value) {
        if ((value & 0xFFFF8000) != 0) {
            output.writeShort((value & 0x7FFF) | 0x8000);
            output.writeByte(value >>> 15);
        } else {
            output.writeShort(value);
        }
    }

    private static String readString(ByteBuf input) {
        var bytes = new byte[MinecraftVarInts.read(input)];
        input.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int readVarShort(ByteBuf input) {
        var low = input.readUnsignedShort();
        var length = low & 0x7FFF;
        if ((low & 0x8000) != 0) {
            length |= input.readUnsignedByte() << 15;
        }
        return length;
    }

    private static void release(ByteBuf buffer) {
        if (buffer != null && buffer.refCnt() > 0) {
            buffer.release();
        }
    }
}

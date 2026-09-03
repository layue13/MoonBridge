package dev.strataproxy.network;

import dev.strataproxy.analysis.CustomPayloadAnomalyPolicy;
import dev.strataproxy.codec.minecraft.MinecraftCompressionCodec;
import dev.strataproxy.codec.minecraft.MinecraftVarInts;
import dev.strataproxy.observability.ProxyMetrics;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FrontendForgeHandshakeGateTest {
    @Test
    void marksLegacyForgeClientFromServerboundRegisterWithoutHostnameToken() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var tracker = new MinecraftForgeHandshakeTracker(4096, profile);
        var metrics = new ProxyMetrics();
        var session = new RelaySession("127.0.0.1:50000");
        var controller = new BackendReplacementController(
                name -> Optional.empty(),
                null,
                metrics,
                NetworkTuning.defaults(),
                CompressionRuntime.defaults(),
                CustomPayloadAnomalyPolicy.defaults(),
                session,
                new RelaySessionRegistry(),
                false,
                25,
                null,
                null,
                profile);
        var backend = new EmbeddedChannel();
        var frontend = new EmbeddedChannel(new FrontendRelayHandler(
                backend,
                metrics,
                "forge-1",
                new MinecraftCompressionAuditState(4096),
                CompressionRuntime.defaults(),
                4096,
                CustomPayloadAnomalyPolicy.defaults(),
                "Steve",
                session.identity(),
                false,
                25,
                controller,
                null,
                null,
                profile,
                null,
                tracker));

        try {
            assertFalse(session.legacyForgeClientDetected());
            assertFalse(frontend.writeInbound(customPayload(0x17, "REGISTER", registerPayload(), false)));

            assertTrue(session.legacyForgeClientDetected());
            var register = (ByteBuf) backend.readOutbound();
            try {
                assertCustomPayload(register, 0x17, "REGISTER", registerPayloadString());
            } finally {
                release(register);
            }
        } finally {
            frontend.finishAndReleaseAll();
            backend.finishAndReleaseAll();
            tracker.close();
        }
    }

    @Test
    void suppressesLegacyForgeRacePacket() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var tracker = new MinecraftForgeHandshakeTracker(4096, profile);
        var backend = new EmbeddedChannel();
        var frontend = new EmbeddedChannel(new FrontendRelayHandler(
                backend,
                new ProxyMetrics(),
                "forge-1",
                new MinecraftCompressionAuditState(4096),
                CompressionRuntime.defaults(),
                4096,
                CustomPayloadAnomalyPolicy.defaults(),
                "Steve",
                new RelaySessionIdentity("127.0.0.1:50000"),
                false,
                25,
                null,
                null,
                null,
                profile,
                null,
                tracker));

        try {
            assertFalse(frontend.writeInbound(customPayload(0x17, "FML", bytes(1), false)));
            assertNull(backend.readOutbound());
        } finally {
            frontend.finishAndReleaseAll();
            backend.finishAndReleaseAll();
            tracker.close();
        }
    }

    @Test
    void forwardsCompleteNonRaceFrameWithoutCopy() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var tracker = new MinecraftForgeHandshakeTracker(4096, profile);
        var backend = new EmbeddedChannel();
        var frontend = new EmbeddedChannel(new FrontendRelayHandler(
                backend,
                new ProxyMetrics(),
                "forge-1",
                new MinecraftCompressionAuditState(4096),
                CompressionRuntime.defaults(),
                4096,
                CustomPayloadAnomalyPolicy.defaults(),
                "Steve",
                new RelaySessionIdentity("127.0.0.1:50000"),
                false,
                25,
                null,
                null,
                null,
                profile,
                null,
                tracker));

        try {
            var frame = frame(packet(0x0B));
            assertFalse(frontend.writeInbound(frame));

            var forwarded = (ByteBuf) backend.readOutbound();
            try {
                assertSame(frame, forwarded);
                assertEquals(0x0B, packetId(forwarded));
            } finally {
                release(forwarded);
            }
        } finally {
            frontend.finishAndReleaseAll();
            backend.finishAndReleaseAll();
            tracker.close();
        }
    }

    @Test
    void suppressesCompressedLegacyForgeRacePacket() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_8);
        var tracker = new MinecraftForgeHandshakeTracker(4096, profile);
        var compression = new MinecraftCompressionAuditState(4096);
        compression.negotiate(0);
        var backend = new EmbeddedChannel();
        var frontend = new EmbeddedChannel(new FrontendRelayHandler(
                backend,
                new ProxyMetrics(),
                "forge-1",
                compression,
                CompressionRuntime.defaults(),
                4096,
                CustomPayloadAnomalyPolicy.defaults(),
                "Steve",
                new RelaySessionIdentity("127.0.0.1:50000"),
                false,
                25,
                null,
                null,
                null,
                profile,
                null,
                tracker));

        try {
            assertFalse(frontend.writeInbound(compressedCustomPayload18(0x17, "FML", bytes(1), 0)));
            assertNull(backend.readOutbound());
        } finally {
            frontend.finishAndReleaseAll();
            backend.finishAndReleaseAll();
            tracker.close();
        }
    }

    @Test
    void suppressesOnlyLegacyForgeRacePacketFromCombinedFrames() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var tracker = new MinecraftForgeHandshakeTracker(4096, profile);
        var backend = new EmbeddedChannel();
        var frontend = new EmbeddedChannel(new FrontendRelayHandler(
                backend,
                new ProxyMetrics(),
                "forge-1",
                new MinecraftCompressionAuditState(4096),
                CompressionRuntime.defaults(),
                4096,
                CustomPayloadAnomalyPolicy.defaults(),
                "Steve",
                new RelaySessionIdentity("127.0.0.1:50000"),
                false,
                25,
                null,
                null,
                null,
                profile,
                null,
                tracker));

        try {
            var combined = combine(
                    customPayload(0x17, "FML", bytes(1), false),
                    customPayload(0x17, "MOD|OK", bytes(7, 8), false));

            assertFalse(frontend.writeInbound(combined));

            var forwarded = (ByteBuf) backend.readOutbound();
            try {
                assertCustomPayload(forwarded, 0x17, "MOD|OK", 7, 8);
                assertNull(backend.readOutbound());
            } finally {
                release(forwarded);
            }
        } finally {
            frontend.finishAndReleaseAll();
            backend.finishAndReleaseAll();
            tracker.close();
        }
    }

    @Test
    void suppressesOnlyCompressedLegacyForgeRacePacketFromCombinedFrames() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_8);
        var tracker = new MinecraftForgeHandshakeTracker(4096, profile);
        var compression = new MinecraftCompressionAuditState(4096);
        compression.negotiate(0);
        var backend = new EmbeddedChannel();
        var frontend = new EmbeddedChannel(new FrontendRelayHandler(
                backend,
                new ProxyMetrics(),
                "forge-1",
                compression,
                CompressionRuntime.defaults(),
                4096,
                CustomPayloadAnomalyPolicy.defaults(),
                "Steve",
                new RelaySessionIdentity("127.0.0.1:50000"),
                false,
                25,
                null,
                null,
                null,
                profile,
                null,
                tracker));

        try {
            var combined = combine(
                    compressedCustomPayload18(0x17, "FML", bytes(1), 0),
                    compressedCustomPayload18(0x17, "MOD|OK", bytes(7, 8), 0));

            assertFalse(frontend.writeInbound(combined));

            var forwarded = (ByteBuf) backend.readOutbound();
            try (var codec = new MinecraftCompressionCodec()) {
                assertCompressedCustomPayload18(codec, forwarded, 0, "MOD|OK", 7, 8);
                assertNull(backend.readOutbound());
            } finally {
                release(forwarded);
            }
        } finally {
            frontend.finishAndReleaseAll();
            backend.finishAndReleaseAll();
            tracker.close();
        }
    }

    @Test
    void queuesNonForgeCustomPayloadsUntilLegacyForgeHandshakeCompletes() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var tracker = new MinecraftForgeHandshakeTracker(4096, profile);
        advanceToPendingComplete(tracker);

        var backend = new EmbeddedChannel();
        var frontend = new EmbeddedChannel(new FrontendRelayHandler(
                backend,
                new ProxyMetrics(),
                "forge-1",
                new MinecraftCompressionAuditState(4096),
                CompressionRuntime.defaults(),
                4096,
                CustomPayloadAnomalyPolicy.defaults(),
                "Steve",
                new RelaySessionIdentity("127.0.0.1:50000"),
                false,
                25,
                null,
                null,
                null,
                profile,
                null,
                tracker));

        try {
            assertFalse(frontend.writeInbound(customPayload(0x17, "MOD|EARLY", bytes(1, 2, 3), false)));
            assertNull(backend.readOutbound());

            assertFalse(frontend.writeInbound(customPayload(0x17, "FML|HS", bytes(255, 5), false)));

            var finalAck = (ByteBuf) backend.readOutbound();
            var queuedPayload = (ByteBuf) backend.readOutbound();
            try {
                assertCustomPayload(finalAck, 0x17, "FML|HS", 255, 5);
                assertCustomPayload(queuedPayload, 0x17, "MOD|EARLY", 1, 2, 3);
                assertTrue(tracker.complete());
            } finally {
                release(finalAck);
                release(queuedPayload);
            }
        } finally {
            frontend.finishAndReleaseAll();
            backend.finishAndReleaseAll();
            tracker.close();
        }
    }

    @Test
    void flushesQueuedPayloadAfterFinalAckWhenBothArriveTogether() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var tracker = new MinecraftForgeHandshakeTracker(4096, profile);
        advanceToPendingComplete(tracker);

        var backend = new EmbeddedChannel();
        var frontend = new EmbeddedChannel(new FrontendRelayHandler(
                backend,
                new ProxyMetrics(),
                "forge-1",
                new MinecraftCompressionAuditState(4096),
                CompressionRuntime.defaults(),
                4096,
                CustomPayloadAnomalyPolicy.defaults(),
                "Steve",
                new RelaySessionIdentity("127.0.0.1:50000"),
                false,
                25,
                null,
                null,
                null,
                profile,
                null,
                tracker));

        try {
            var combined = combine(
                    customPayload(0x17, "MOD|EARLY", bytes(1, 2, 3), false),
                    customPayload(0x17, "FML|HS", bytes(255, 5), false));

            assertFalse(frontend.writeInbound(combined));

            var finalAck = (ByteBuf) backend.readOutbound();
            var queuedPayload = (ByteBuf) backend.readOutbound();
            try {
                assertCustomPayload(finalAck, 0x17, "FML|HS", 255, 5);
                assertCustomPayload(queuedPayload, 0x17, "MOD|EARLY", 1, 2, 3);
                assertTrue(tracker.complete());
            } finally {
                release(finalAck);
                release(queuedPayload);
            }
        } finally {
            frontend.finishAndReleaseAll();
            backend.finishAndReleaseAll();
            tracker.close();
        }
    }

    @Test
    void forwardsRegularPlayFramesDuringLegacyForgeHandshake() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var tracker = new MinecraftForgeHandshakeTracker(4096, profile);
        advanceToPendingComplete(tracker);

        var backend = new EmbeddedChannel();
        var frontend = new EmbeddedChannel(new FrontendRelayHandler(
                backend,
                new ProxyMetrics(),
                "forge-1",
                new MinecraftCompressionAuditState(4096),
                CompressionRuntime.defaults(),
                4096,
                CustomPayloadAnomalyPolicy.defaults(),
                "Steve",
                new RelaySessionIdentity("127.0.0.1:50000"),
                false,
                25,
                null,
                null,
                null,
                profile,
                null,
                tracker));

        try {
            assertFalse(frontend.writeInbound(frame(packet(0x0B))));

            var play = (ByteBuf) backend.readOutbound();
            try {
                assertEquals(0x0B, packetId(play));
                assertFalse(tracker.complete());
            } finally {
                release(play);
            }
        } finally {
            frontend.finishAndReleaseAll();
            backend.finishAndReleaseAll();
            tracker.close();
        }
    }

    @Test
    void removesForgeHandshakeStateWhenFrontendRelayCloses() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var tracker = new MinecraftForgeHandshakeTracker(4096, profile);
        advanceToPendingComplete(tracker);
        var metrics = new ProxyMetrics();
        var backend = new EmbeddedChannel();
        var frontend = new EmbeddedChannel(new FrontendRelayHandler(
                backend,
                metrics,
                "forge-1",
                new MinecraftCompressionAuditState(4096),
                CompressionRuntime.defaults(),
                4096,
                CustomPayloadAnomalyPolicy.defaults(),
                "Steve",
                new RelaySessionIdentity("127.0.0.1:50000"),
                false,
                25,
                null,
                null,
                null,
                profile,
                null,
                tracker));

        try {
            assertFalse(frontend.writeInbound(customPayload(0x17, "FML|HS", bytes(255, 5), false)));
            var finalAck = (ByteBuf) backend.readOutbound();
            release(finalAck);
            assertEquals(1, metrics.snapshot().forgeHandshakes().size());

            frontend.close();
            frontend.runPendingTasks();

            assertTrue(metrics.snapshot().forgeHandshakes().isEmpty());
        } finally {
            frontend.finishAndReleaseAll();
            backend.finishAndReleaseAll();
            tracker.close();
        }
    }

    @Test
    void tracksCompressedClientPluginChannelsThroughFrontendRelay() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_8);
        var metrics = new ProxyMetrics();
        var session = new RelaySession("127.0.0.1:50000");
        var controller = new BackendReplacementController(
                name -> Optional.empty(),
                null,
                metrics,
                NetworkTuning.defaults(),
                CompressionRuntime.defaults(),
                CustomPayloadAnomalyPolicy.defaults(),
                session,
                new RelaySessionRegistry(),
                false,
                25,
                null,
                null,
                profile);
        var compression = new MinecraftCompressionAuditState(4096);
        compression.negotiate(0);
        var backend = new EmbeddedChannel();
        var frontend = new EmbeddedChannel(new FrontendRelayHandler(
                backend,
                metrics,
                "forge-1",
                compression,
                CompressionRuntime.defaults(),
                4096,
                CustomPayloadAnomalyPolicy.defaults(),
                "Steve",
                session.identity(),
                false,
                25,
                controller,
                null,
                null,
                profile,
                null,
                null));

        try {
            assertFalse(frontend.writeInbound(compressedCustomPayload18("REGISTER", registerPayloadString(), 0)));

            assertEquals(Set.of("FML|HS", "FML", "FML|MP", "FORGE"),
                    session.pluginChannelRegistry().channels());
            var forwarded = (ByteBuf) backend.readOutbound();
            try (var codec = new MinecraftCompressionCodec()) {
                assertCompressedCustomPayload18(codec, forwarded, 0, "REGISTER", registerPayloadString());
            } finally {
                release(forwarded);
            }
        } finally {
            frontend.finishAndReleaseAll();
            backend.finishAndReleaseAll();
            session.closePluginChannelRegistry();
        }
    }

    @Test
    void marksLegacyForgeClientFromCompressedServerboundRegister() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_8);
        var tracker = new MinecraftForgeHandshakeTracker(4096, profile);
        var metrics = new ProxyMetrics();
        var session = new RelaySession("127.0.0.1:50000");
        var controller = new BackendReplacementController(
                name -> Optional.empty(),
                null,
                metrics,
                NetworkTuning.defaults(),
                CompressionRuntime.defaults(),
                CustomPayloadAnomalyPolicy.defaults(),
                session,
                new RelaySessionRegistry(),
                false,
                25,
                null,
                null,
                profile);
        var compression = new MinecraftCompressionAuditState(4096);
        compression.negotiate(0);
        var backend = new EmbeddedChannel();
        var frontend = new EmbeddedChannel(new FrontendRelayHandler(
                backend,
                metrics,
                "forge-1",
                compression,
                CompressionRuntime.defaults(),
                4096,
                CustomPayloadAnomalyPolicy.defaults(),
                "Steve",
                session.identity(),
                false,
                25,
                controller,
                null,
                null,
                profile,
                null,
                tracker));

        try {
            assertFalse(session.legacyForgeClientDetected());
            assertFalse(frontend.writeInbound(compressedCustomPayload18("REGISTER", registerPayloadString(), 0)));

            assertTrue(session.legacyForgeClientDetected());
            assertEquals(MinecraftForgeHandshakeTracker.Stage.CHANNELS_REGISTERED, tracker.stage());
            var forwarded = (ByteBuf) backend.readOutbound();
            try (var codec = new MinecraftCompressionCodec()) {
                assertCompressedCustomPayload18(codec, forwarded, 0, "REGISTER", registerPayloadString());
            } finally {
                release(forwarded);
            }
        } finally {
            frontend.finishAndReleaseAll();
            backend.finishAndReleaseAll();
            tracker.close();
            session.closePluginChannelRegistry();
        }
    }

    @Test
    void queuesCompressedNonForgeCustomPayloadsUntilLegacyForgeHandshakeCompletes() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_8);
        var tracker = new MinecraftForgeHandshakeTracker(4096, profile);
        advanceCompressed18ToPendingComplete(tracker);
        var compression = new MinecraftCompressionAuditState(4096);
        compression.negotiate(0);
        var backend = new EmbeddedChannel();
        var frontend = new EmbeddedChannel(new FrontendRelayHandler(
                backend,
                new ProxyMetrics(),
                "forge-1",
                compression,
                CompressionRuntime.defaults(),
                4096,
                CustomPayloadAnomalyPolicy.defaults(),
                "Steve",
                new RelaySessionIdentity("127.0.0.1:50000"),
                false,
                25,
                null,
                null,
                null,
                profile,
                null,
                tracker));

        try {
            assertFalse(frontend.writeInbound(compressedCustomPayload18(0x17, "MOD|EARLY", bytes(1, 2, 3), 0)));
            assertNull(backend.readOutbound());

            assertFalse(frontend.writeInbound(compressedCustomPayload18(0x17, "FML|HS", bytes(255, 5), 0)));

            var finalAck = (ByteBuf) backend.readOutbound();
            var queuedPayload = (ByteBuf) backend.readOutbound();
            try (var codec = new MinecraftCompressionCodec()) {
                assertCompressedCustomPayload18(codec, finalAck, 0, "FML|HS", 255, 5);
                assertCompressedCustomPayload18(codec, queuedPayload, 0, "MOD|EARLY", 1, 2, 3);
                assertTrue(tracker.complete());
            } finally {
                release(finalAck);
                release(queuedPayload);
            }
        } finally {
            frontend.finishAndReleaseAll();
            backend.finishAndReleaseAll();
            tracker.close();
        }
    }

    private static void advanceToPendingComplete(MinecraftForgeHandshakeTracker tracker) {
        observe(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                customPayload(0x3F, "FML|HS", bytes(0, 2, 0, 0, 0, 0), true));
        observe(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                customPayload(0x17, "REGISTER", registerPayload(), false));
        observe(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                customPayload(0x17, "FML|HS", bytes(1, 2), false));
        observe(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                customPayload(0x17, "FML|HS", modListPayload(), false));
        observe(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                customPayload(0x3F, "FML|HS", modListPayload(), true));
        observe(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                customPayload(0x17, "FML|HS", bytes(255, 2), false));
        observe(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                customPayload(0x3F, "FML|HS", bytes(3, 0), true));
        observe(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                customPayload(0x17, "FML|HS", bytes(255, 3), false));
        observe(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                customPayload(0x3F, "FML|HS", bytes(255, 2), true));
        observe(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                customPayload(0x17, "FML|HS", bytes(255, 4), false));
        observe(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                customPayload(0x3F, "FML|HS", bytes(255, 3), true));
        assertFalse(tracker.complete());
        assertTrue(tracker.backendSwitchBlocked());
    }

    private static void advanceCompressed18ToPendingComplete(MinecraftForgeHandshakeTracker tracker) {
        observeCompressed(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                compressedCustomPayload18(0x3F, "FML|HS", bytes(0, 2, 0, 0, 0, 0), 0));
        observeCompressed(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                compressedCustomPayload18(0x17, "REGISTER", registerPayload(), 0));
        observeCompressed(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                compressedCustomPayload18(0x17, "FML|HS", bytes(1, 2), 0));
        observeCompressed(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                compressedCustomPayload18(0x17, "FML|HS", modListPayload(), 0));
        observeCompressed(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                compressedCustomPayload18(0x3F, "FML|HS", modListPayload(), 0));
        observeCompressed(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                compressedCustomPayload18(0x17, "FML|HS", bytes(255, 2), 0));
        observeCompressed(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                compressedCustomPayload18(0x3F, "FML|HS", bytes(3, 0), 0));
        observeCompressed(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                compressedCustomPayload18(0x17, "FML|HS", bytes(255, 3), 0));
        observeCompressed(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                compressedCustomPayload18(0x3F, "FML|HS", bytes(255, 2), 0));
        observeCompressed(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                compressedCustomPayload18(0x17, "FML|HS", bytes(255, 4), 0));
        observeCompressed(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                compressedCustomPayload18(0x3F, "FML|HS", bytes(255, 3), 0));
        assertFalse(tracker.complete());
        assertTrue(tracker.backendSwitchBlocked());
    }

    private static void observe(
            MinecraftForgeHandshakeTracker tracker,
            MinecraftForgeHandshakeTracker.Direction direction,
            ByteBuf frame) {
        try {
            tracker.observe(direction, frame);
        } finally {
            frame.release();
        }
    }

    private static void observeCompressed(
            MinecraftForgeHandshakeTracker tracker,
            MinecraftForgeHandshakeTracker.Direction direction,
            ByteBuf frame) {
        try {
            tracker.observeCompressed(direction, UnpooledByteBufAllocator.DEFAULT, frame, 0);
        } finally {
            frame.release();
        }
    }

    private static ByteBuf customPayload(int packetId, String channel, ByteBuf payload, boolean clientbound) {
        var packet = Unpooled.buffer();
        try {
            MinecraftVarInts.write(packet, packetId);
            writeString(packet, channel);
            if (clientbound) {
                writeVarShort(packet, payload.readableBytes());
            } else {
                packet.writeShort(payload.readableBytes());
            }
            packet.writeBytes(payload, payload.readerIndex(), payload.readableBytes());
            return frame(packet);
        } finally {
            payload.release();
        }
    }

    private static ByteBuf compressedCustomPayload18(String channel, String payloadText, int threshold) {
        var payload = Unpooled.wrappedBuffer(payloadText.getBytes(StandardCharsets.UTF_8));
        try {
            return compressedCustomPayload18(0x17, channel, payload.retainedDuplicate(), threshold);
        } finally {
            payload.release();
        }
    }

    private static ByteBuf compressedCustomPayload18(int packetId, String channel, ByteBuf payload, int threshold) {
        var packet = Unpooled.buffer();
        try (var codec = new MinecraftCompressionCodec()) {
            MinecraftVarInts.write(packet, packetId);
            writeString(packet, channel);
            packet.writeBytes(payload, payload.readerIndex(), payload.readableBytes());
            return codec.encodeFrame(UnpooledByteBufAllocator.DEFAULT, packet, threshold);
        } finally {
            payload.release();
            packet.release();
        }
    }

    private static ByteBuf registerPayload() {
        return Unpooled.wrappedBuffer(registerPayloadString().getBytes(StandardCharsets.UTF_8));
    }

    private static String registerPayloadString() {
        return "FML|HS\0FML\0FML|MP\0FORGE";
    }

    private static ByteBuf modListPayload() {
        var payload = Unpooled.buffer();
        payload.writeByte(2);
        MinecraftVarInts.write(payload, 1);
        writeString(payload, "mcp");
        writeString(payload, "9.05");
        return payload;
    }

    private static ByteBuf frame(ByteBuf packet) {
        var frame = Unpooled.buffer();
        MinecraftVarInts.write(frame, packet.readableBytes());
        frame.writeBytes(packet, packet.readerIndex(), packet.readableBytes());
        packet.release();
        return frame;
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

    private static ByteBuf packet(int packetId) {
        var packet = Unpooled.buffer();
        MinecraftVarInts.write(packet, packetId);
        return packet;
    }

    private static ByteBuf bytes(int... values) {
        var payload = Unpooled.buffer(values.length);
        for (var value : values) {
            payload.writeByte(value);
        }
        return payload;
    }

    private static int packetId(ByteBuf frame) {
        var payload = payload(frame);
        try {
            return MinecraftVarInts.read(payload);
        } finally {
            payload.release();
        }
    }

    private static void assertCustomPayload(ByteBuf frame, int packetId, String channel, int... data) {
        var payload = payload(frame);
        try {
            assertEquals(packetId, MinecraftVarInts.read(payload));
            assertEquals(channel, readString(payload));
            assertEquals(data.length, payload.readUnsignedShort());
            for (var value : data) {
                assertEquals(value, payload.readUnsignedByte());
            }
            assertEquals(0, payload.readableBytes());
        } finally {
            payload.release();
        }
    }

    private static void assertCustomPayload(ByteBuf frame, int packetId, String channel, String data) {
        var payload = payload(frame);
        try {
            assertEquals(packetId, MinecraftVarInts.read(payload));
            assertEquals(channel, readString(payload));
            assertEquals(data.length(), payload.readUnsignedShort());
            assertEquals(data, payload.readCharSequence(data.length(), StandardCharsets.UTF_8).toString());
            assertEquals(0, payload.readableBytes());
        } finally {
            payload.release();
        }
    }

    private static void assertCompressedCustomPayload18(
            MinecraftCompressionCodec codec,
            ByteBuf frame,
            int threshold,
            String channel,
            String data) {
        var packet = codec.decodeFrame(UnpooledByteBufAllocator.DEFAULT, frame, threshold, 4096);
        try {
            assertEquals(0x17, MinecraftVarInts.read(packet));
            assertEquals(channel, readString(packet));
            assertEquals(data, packet.readCharSequence(packet.readableBytes(), StandardCharsets.UTF_8).toString());
            assertEquals(0, packet.readableBytes());
        } finally {
            packet.release();
        }
    }

    private static void assertCompressedCustomPayload18(
            MinecraftCompressionCodec codec,
            ByteBuf frame,
            int threshold,
            String channel,
            int... data) {
        var packet = codec.decodeFrame(UnpooledByteBufAllocator.DEFAULT, frame, threshold, 4096);
        try {
            assertEquals(0x17, MinecraftVarInts.read(packet));
            assertEquals(channel, readString(packet));
            for (var value : data) {
                assertEquals(value, packet.readUnsignedByte());
            }
            assertEquals(0, packet.readableBytes());
        } finally {
            packet.release();
        }
    }

    private static ByteBuf payload(ByteBuf frame) {
        var length = MinecraftVarInts.read(frame);
        return frame.readRetainedSlice(length);
    }

    private static void writeString(ByteBuf output, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        MinecraftVarInts.write(output, bytes.length);
        output.writeBytes(bytes);
    }

    private static String readString(ByteBuf input) {
        return MinecraftProtocolCodec.readString(input, 32767);
    }

    private static void writeVarShort(ByteBuf output, int value) {
        if ((value & 0xFFFF8000) != 0) {
            output.writeShort((value & 0x7FFF) | 0x8000);
            output.writeByte(value >>> 15);
        } else {
            output.writeShort(value);
        }
    }

    private static void release(ByteBuf buffer) {
        if (buffer != null && buffer.refCnt() > 0) {
            buffer.release();
        }
    }
}

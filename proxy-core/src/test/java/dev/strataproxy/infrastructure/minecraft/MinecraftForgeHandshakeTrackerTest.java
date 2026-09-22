package dev.strataproxy.infrastructure.minecraft;

import dev.strataproxy.infrastructure.minecraft.codec.MinecraftCompressionCodec;
import dev.strataproxy.infrastructure.minecraft.codec.MinecraftVarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftForgeHandshakeTrackerTest {
    @Test
    void backendRelaySendsResetBeforeNewForgeServerRegistrationWhenPreviousHandshakeCompleted() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var tracker = new MinecraftForgeHandshakeTracker(4096, profile, true);
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
                tracker));

        backend.writeInbound(customPayload(0x3F, "REGISTER", registerPayload(), true));

        var reset = (ByteBuf) frontend.readOutbound();
        var register = (ByteBuf) frontend.readOutbound();
        try {
            assertForgeReset(reset);
            assertEquals(0x3F, packetId(register));
        } finally {
            release(reset);
            release(register);
            backend.finishAndReleaseAll();
            frontend.finishAndReleaseAll();
            tracker.close();
        }
    }

    @Test
    void backendRelaySendsCompressedResetBeforeNew18ForgeServerRegistrationWhenCompressionNegotiated() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_8);
        var tracker = new MinecraftForgeHandshakeTracker(4096, profile, true);
        var compression = new MinecraftCompressionAuditState(4096);
        compression.negotiate(0);
        var frontend = new EmbeddedChannel();
        var backend = new EmbeddedChannel(new BackendRelayHandler(
                frontend,
                new ProxyMetrics(),
                "forge-1",
                4096,
                compression,
                CompressionRuntime.defaults(),
                new RelaySessionIdentity("127.0.0.1:50000"),
                MinecraftForwardingRuntime.none(),
                false,
                25,
                profile,
                null,
                tracker));

        backend.writeInbound(compressedCustomPayload18(0x3F, "REGISTER", registerPayload(), 0));

        var reset = (ByteBuf) frontend.readOutbound();
        var register = (ByteBuf) frontend.readOutbound();
        try (var codec = new MinecraftCompressionCodec()) {
            assertCompressedForgeReset(codec, reset, 0);
            assertCompressedCustomPayload18(codec, register, 0, 0x3F, "REGISTER", registerPayloadString());
        } finally {
            release(reset);
            release(register);
            backend.finishAndReleaseAll();
            frontend.finishAndReleaseAll();
            tracker.close();
        }
    }

    @Test
    void tracksLegacyForgeHandshakeToComplete() {
        var tracker = tracker();
        try {
            assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                    customPayload(0x3F, "REGISTER", registerPayload(), true), "register");
            assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                    customPayload(0x3F, "FML|HS", bytes(0, 2, 0, 0, 0, 0), true), "server_hello");
            assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                    customPayload(0x17, "REGISTER", registerPayload(), false), "register");
            assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                    customPayload(0x17, "FML|HS", bytes(1, 2), false), "client_hello");
            assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                    customPayload(0x17, "FML|HS", modListPayload(), false), "mod_list");
            assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                    customPayload(0x3F, "FML|HS", modListPayload(), true), "mod_list");
            assertEquals(1, tracker.clientModCount());
            assertEquals("9.05", tracker.clientMods().get("mcp"));
            assertEquals(1, tracker.serverModCount());
            assertEquals("9.05", tracker.serverMods().get("mcp"));
            assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                    customPayload(0x17, "FML|HS", bytes(255, 2), false), "handshake_ack");
            assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                    customPayload(0x3F, "FML|HS", bytes(3, 0), true), "registry_data");
            assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                    customPayload(0x17, "FML|HS", bytes(255, 3), false), "handshake_ack");
            assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                    customPayload(0x3F, "FML|HS", bytes(255, 2), true), "handshake_ack");
            assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                    customPayload(0x17, "FML|HS", bytes(255, 4), false), "handshake_ack");
            assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                    customPayload(0x3F, "FML|HS", bytes(255, 3), true), "handshake_ack");
            assertEquals(MinecraftForgeHandshakeTracker.BackendPhase.COMPLETE, tracker.backendPhase());
            assertEquals(MinecraftForgeHandshakeTracker.ClientPhase.PENDING_COMPLETE, tracker.clientPhase());
            assertFalse(tracker.complete());
            assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                    customPayload(0x17, "FML|HS", bytes(255, 5), false), "handshake_ack");

            assertEquals(MinecraftForgeHandshakeTracker.Stage.COMPLETE, tracker.stage());
            assertEquals(MinecraftForgeHandshakeTracker.ClientPhase.COMPLETE, tracker.clientPhase());
            assertEquals(MinecraftForgeHandshakeTracker.BackendPhase.COMPLETE, tracker.backendPhase());
            assertTrue(tracker.complete());
        } finally {
            tracker.close();
        }
    }

    @Test
    void tracksCompressedLegacyForgeHandshakeToComplete() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_8);
        var tracker = new MinecraftForgeHandshakeTracker(4096, profile);
        try {
            assertCompressedEvent(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                    compressedCustomPayload18(0x3F, "REGISTER", registerPayload(), 0), "register");
            assertCompressedEvent(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                    compressedCustomPayload18(0x3F, "FML|HS", bytes(0, 2, 0, 0, 0, 0), 0), "server_hello");
            assertCompressedEvent(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                    compressedCustomPayload18(0x17, "REGISTER", registerPayload(), 0), "register");
            assertCompressedEvent(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                    compressedCustomPayload18(0x17, "FML|HS", bytes(1, 2), 0), "client_hello");
            assertCompressedEvent(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                    compressedCustomPayload18(0x17, "FML|HS", modListPayload(), 0), "mod_list");
            assertCompressedEvent(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                    compressedCustomPayload18(0x3F, "FML|HS", modListPayload(), 0), "mod_list");
            assertCompressedEvent(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                    compressedCustomPayload18(0x17, "FML|HS", bytes(255, 2), 0), "handshake_ack");
            assertCompressedEvent(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                    compressedCustomPayload18(0x3F, "FML|HS", bytes(3, 0), 0), "registry_data");
            assertCompressedEvent(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                    compressedCustomPayload18(0x17, "FML|HS", bytes(255, 3), 0), "handshake_ack");
            assertCompressedEvent(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                    compressedCustomPayload18(0x3F, "FML|HS", bytes(255, 2), 0), "handshake_ack");
            assertCompressedEvent(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                    compressedCustomPayload18(0x17, "FML|HS", bytes(255, 4), 0), "handshake_ack");
            assertCompressedEvent(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                    compressedCustomPayload18(0x3F, "FML|HS", bytes(255, 3), 0), "handshake_ack");
            assertCompressedEvent(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                    compressedCustomPayload18(0x17, "FML|HS", bytes(255, 5), 0), "handshake_ack");

            assertEquals(MinecraftForgeHandshakeTracker.Stage.COMPLETE, tracker.stage());
            assertEquals(MinecraftForgeHandshakeTracker.ClientPhase.COMPLETE, tracker.clientPhase());
            assertEquals(MinecraftForgeHandshakeTracker.BackendPhase.COMPLETE, tracker.backendPhase());
            assertTrue(tracker.complete());
            assertFalse(tracker.backendSwitchBlocked());
        } finally {
            tracker.close();
        }
    }

    @Test
    void tracksMultipartRegistryData() {
        var tracker = tracker();
        try {
            advanceToWaitingServerData(tracker);

            assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                    customPayload(0x3F, "FML|MP", multipartStartPayload("FML|HS", 2, 6), true),
                    "multipart_start");

            var events = tracker.observe(MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                    customPayload(0x3F, "FML|MP", multipartPartPayload(0, 3, 1, 2), true));
            assertEquals(2, events.size());
            assertEquals("multipart_part", events.get(0).type());
            assertTrue(events.get(0).expected());
            assertEquals("registry_data_multipart", events.get(1).type());
            assertTrue(events.get(1).expected());
            assertEquals(MinecraftForgeHandshakeTracker.Stage.REGISTRY_DATA, tracker.stage());

            assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                    customPayload(0x3F, "FML|MP", multipartPartPayload(1, 3, 4, 5), true),
                    "multipart_part", "multipart_complete");

            assertEquals(1, tracker.clientboundRegistryPackets());
            assertEquals(5, tracker.clientboundRegistryBytes());
        } finally {
            tracker.close();
        }
    }

    @Test
    void resetAllowsHandshakeToRestart() {
        var tracker = tracker();
        try {
            assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                    customPayload(0x3F, "FML|HS", bytes(254, 0), true), "handshake_reset");
            assertEquals(MinecraftForgeHandshakeTracker.Stage.RESET, tracker.stage());
            assertEquals(MinecraftForgeHandshakeTracker.ClientPhase.RESET, tracker.clientPhase());
            assertEquals(MinecraftForgeHandshakeTracker.BackendPhase.RESET, tracker.backendPhase());

            assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                    customPayload(0x3F, "FML|HS", bytes(0, 2, 0, 0, 0, 0), true), "server_hello");

            assertEquals(MinecraftForgeHandshakeTracker.Stage.SERVER_HELLO, tracker.stage());
            assertEquals(MinecraftForgeHandshakeTracker.ClientPhase.SERVER_HELLO_RECEIVED, tracker.clientPhase());
            assertEquals(MinecraftForgeHandshakeTracker.BackendPhase.SERVER_HELLO_SENT, tracker.backendPhase());
        } finally {
            tracker.close();
        }
    }

    @Test
    void staleAckAfterResetDoesNotAdvanceHandshake() {
        var tracker = tracker();
        try {
            assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                    customPayload(0x3F, "FML|HS", bytes(254, 0), true), "handshake_reset");

            assertUnexpectedEvent(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                    customPayload(0x17, "FML|HS", bytes(255, 5), false), "handshake_ack");

            assertEquals(MinecraftForgeHandshakeTracker.Stage.RESET, tracker.stage());
            assertEquals(MinecraftForgeHandshakeTracker.ClientPhase.RESET, tracker.clientPhase());
            assertEquals(MinecraftForgeHandshakeTracker.BackendPhase.RESET, tracker.backendPhase());
            assertFalse(tracker.complete());
            assertFalse(tracker.backendSwitchBlocked());

            assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                    customPayload(0x3F, "FML|HS", bytes(0, 2, 0, 0, 0, 0), true), "server_hello");
            assertEquals(MinecraftForgeHandshakeTracker.Stage.SERVER_HELLO, tracker.stage());
        } finally {
            tracker.close();
        }
    }

    @Test
    void resetClearsPreviousForgeHandshakeData() {
        var tracker = tracker();
        try {
            advanceToComplete(tracker);
            assertEquals(1, tracker.clientModCount());
            assertEquals(1, tracker.serverModCount());
            assertEquals(1, tracker.clientboundRegistryPackets());
            assertTrue(tracker.clientboundRegistryBytes() > 0);

            assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                    customPayload(0x3F, "FML|HS", bytes(254, 0), true), "handshake_reset");

            assertEquals(MinecraftForgeHandshakeTracker.Stage.RESET, tracker.stage());
            assertEquals(0, tracker.clientModCount());
            assertEquals(0, tracker.serverModCount());
            assertEquals(0, tracker.clientboundRegistryPackets());
            assertEquals(0, tracker.clientboundRegistryBytes());
            assertTrue(tracker.clientMods().isEmpty());
            assertTrue(tracker.serverMods().isEmpty());
            assertFalse(tracker.backendSwitchBlocked());
        } finally {
            tracker.close();
        }
    }

    @Test
    void resetDropsStaleServerboundPartialFrame() {
        var tracker = tracker();
        try {
            var stalePartial = Unpooled.wrappedBuffer(new byte[] {0x7F});
            try {
                assertTrue(tracker.observe(MinecraftForgeHandshakeTracker.Direction.SERVERBOUND, stalePartial).isEmpty());
            } finally {
                stalePartial.release();
            }

            assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                    customPayload(0x3F, "FML|HS", bytes(254, 0), true), "handshake_reset");

            assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                    customPayload(0x17, "REGISTER", registerPayload(), false), "register");
            assertEquals(MinecraftForgeHandshakeTracker.Stage.CHANNELS_REGISTERED, tracker.stage());
            assertEquals(MinecraftForgeHandshakeTracker.ClientPhase.CHANNELS_REGISTERED, tracker.clientPhase());
        } finally {
            tracker.close();
        }
    }

    @Test
    void proxyInjectedResetClearsTrackerStateAndAllowsRestart() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var tracker = new MinecraftForgeHandshakeTracker(4096, profile, true);
        try {
            assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                    customPayload(0x3F, "REGISTER", registerPayload(), true), "register");
            assertTrue(tracker.consumeResetRequiredOnNextForgeServer());

            tracker.resetHandshakeFromProxy();

            assertEquals(MinecraftForgeHandshakeTracker.Stage.RESET, tracker.stage());
            assertEquals(MinecraftForgeHandshakeTracker.ClientPhase.RESET, tracker.clientPhase());
            assertEquals(MinecraftForgeHandshakeTracker.BackendPhase.RESET, tracker.backendPhase());
            assertFalse(tracker.backendSwitchBlocked());

            assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                    customPayload(0x3F, "FML|HS", bytes(0, 2, 0, 0, 0, 0), true), "server_hello");
            assertEquals(MinecraftForgeHandshakeTracker.Stage.SERVER_HELLO, tracker.stage());
            assertEquals(MinecraftForgeHandshakeTracker.ClientPhase.SERVER_HELLO_RECEIVED, tracker.clientPhase());
            assertEquals(MinecraftForgeHandshakeTracker.BackendPhase.SERVER_HELLO_SENT, tracker.backendPhase());
        } finally {
            tracker.close();
        }
    }

    @Test
    void staleAckAfterProxyInjectedResetDoesNotAdvanceHandshake() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var tracker = new MinecraftForgeHandshakeTracker(4096, profile, true);
        try {
            assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                    customPayload(0x3F, "REGISTER", registerPayload(), true), "register");
            assertTrue(tracker.consumeResetRequiredOnNextForgeServer());

            tracker.resetHandshakeFromProxy();

            assertUnexpectedEvent(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                    customPayload(0x17, "FML|HS", bytes(255, 5), false), "handshake_ack");

            assertEquals(MinecraftForgeHandshakeTracker.Stage.RESET, tracker.stage());
            assertEquals(MinecraftForgeHandshakeTracker.ClientPhase.RESET, tracker.clientPhase());
            assertEquals(MinecraftForgeHandshakeTracker.BackendPhase.RESET, tracker.backendPhase());
            assertFalse(tracker.complete());
            assertFalse(tracker.backendSwitchBlocked());

            assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                    customPayload(0x3F, "FML|HS", bytes(0, 2, 0, 0, 0, 0), true), "server_hello");
            assertEquals(MinecraftForgeHandshakeTracker.Stage.SERVER_HELLO, tracker.stage());
        } finally {
            tracker.close();
        }
    }

    @Test
    void proxyInjectedResetDropsStaleClientboundPartialFrame() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var tracker = new MinecraftForgeHandshakeTracker(4096, profile, true);
        try {
            var stalePartial = Unpooled.wrappedBuffer(new byte[] {0x7F});
            try {
                assertTrue(tracker.observe(MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND, stalePartial).isEmpty());
            } finally {
                stalePartial.release();
            }

            tracker.resetHandshakeFromProxy();

            assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                    customPayload(0x3F, "FML|HS", bytes(0, 2, 0, 0, 0, 0), true), "server_hello");
            assertEquals(MinecraftForgeHandshakeTracker.Stage.SERVER_HELLO, tracker.stage());
        } finally {
            tracker.close();
        }
    }

    @Test
    void registerChannelMatchingIsExact() {
        var tracker = tracker();
        try {
            assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                    customPayload(0x3F, "REGISTER", registerPayload("NOT_FML|HS_SUFFIX\0OTHER"), true),
                    "register_other");

            assertEquals(MinecraftForgeHandshakeTracker.Stage.IDLE, tracker.stage());
            assertEquals(MinecraftForgeHandshakeTracker.ClientPhase.NOT_STARTED, tracker.clientPhase());
            assertEquals(MinecraftForgeHandshakeTracker.BackendPhase.NOT_STARTED, tracker.backendPhase());
            assertFalse(tracker.backendSwitchBlocked());
        } finally {
            tracker.close();
        }
    }

    @Test
    void blocksBackendSwitchUntilBothForgePhasesComplete() {
        var tracker = tracker();
        try {
            assertFalse(tracker.backendSwitchBlocked());

            assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                    customPayload(0x3F, "REGISTER", registerPayload(), true), "register");

            assertTrue(tracker.backendSwitchBlocked());

            advanceToComplete(tracker);

            assertFalse(tracker.backendSwitchBlocked());
        } finally {
            tracker.close();
        }
    }

    @Test
    void clientOnlyForgeChannelRegistrationDoesNotBlockBackendSwitch() {
        var tracker = tracker();
        try {
            assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                    customPayload(0x17, "REGISTER", registerPayload(), false), "register");

            assertEquals(MinecraftForgeHandshakeTracker.Stage.CHANNELS_REGISTERED, tracker.stage());
            assertEquals(MinecraftForgeHandshakeTracker.ClientPhase.CHANNELS_REGISTERED, tracker.clientPhase());
            assertEquals(MinecraftForgeHandshakeTracker.BackendPhase.NOT_STARTED, tracker.backendPhase());
            assertFalse(tracker.backendSwitchBlocked());
        } finally {
            tracker.close();
        }
    }

    @Test
    void unexpectedServerboundModListDoesNotAdvanceHandshake() {
        var tracker = tracker();
        try {
            assertUnexpectedEvent(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                    customPayload(0x17, "FML|HS", modListPayload(), false), "mod_list");

            assertEquals(MinecraftForgeHandshakeTracker.Stage.IDLE, tracker.stage());
            assertEquals(MinecraftForgeHandshakeTracker.ClientPhase.NOT_STARTED, tracker.clientPhase());
            assertEquals(MinecraftForgeHandshakeTracker.BackendPhase.NOT_STARTED, tracker.backendPhase());
            assertEquals(0, tracker.clientModCount());
            assertFalse(tracker.complete());
            assertFalse(tracker.backendSwitchBlocked());
        } finally {
            tracker.close();
        }
    }

    @Test
    void unexpectedFinalClientAckDoesNotCompleteHandshake() {
        var tracker = tracker();
        try {
            assertUnexpectedEvent(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                    customPayload(0x17, "FML|HS", bytes(255, 5), false), "handshake_ack");

            assertEquals(MinecraftForgeHandshakeTracker.Stage.IDLE, tracker.stage());
            assertEquals(MinecraftForgeHandshakeTracker.ClientPhase.NOT_STARTED, tracker.clientPhase());
            assertEquals(MinecraftForgeHandshakeTracker.BackendPhase.NOT_STARTED, tracker.backendPhase());
            assertFalse(tracker.complete());
            assertFalse(tracker.backendSwitchBlocked());
        } finally {
            tracker.close();
        }
    }

    @Test
    void unexpectedBackendCompleteAckDoesNotAdvanceBackendPhase() {
        var tracker = tracker();
        try {
            advanceToWaitingServerData(tracker);

            assertUnexpectedEvent(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                    customPayload(0x3F, "FML|HS", bytes(255, 3), true), "handshake_ack");

            assertEquals(MinecraftForgeHandshakeTracker.Stage.WAITING_SERVER_DATA, tracker.stage());
            assertEquals(MinecraftForgeHandshakeTracker.BackendPhase.MOD_LIST_SENT, tracker.backendPhase());
            assertFalse(tracker.complete());
            assertTrue(tracker.backendSwitchBlocked());
        } finally {
            tracker.close();
        }
    }

    @Test
    void observesSplitCompressedFramesAfterFirstInputIsReleased() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_8);
        var tracker = new MinecraftForgeHandshakeTracker(4096, profile);
        var frame = compressedCustomPayload18(0x17, "REGISTER", registerPayload(), 0);
        try {
            var first = frame.readRetainedSlice(2);
            var second = frame.readRetainedSlice(frame.readableBytes());
            try {
                assertTrue(tracker.observeCompressed(
                                MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                                UnpooledByteBufAllocator.DEFAULT,
                                first,
                                0)
                        .isEmpty());
            } finally {
                first.release();
            }

            try {
                var events = tracker.observeCompressed(
                        MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                        UnpooledByteBufAllocator.DEFAULT,
                        second,
                        0);
                assertEquals(1, events.size());
                assertEquals("register", events.get(0).type());
                assertTrue(events.get(0).expected());
            } finally {
                second.release();
            }
        } finally {
            frame.release();
            tracker.close();
        }
    }

    private static MinecraftForgeHandshakeTracker tracker() {
        return new MinecraftForgeHandshakeTracker(
                4096,
                MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10));
    }

    private static void advanceToWaitingServerData(MinecraftForgeHandshakeTracker tracker) {
        assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                customPayload(0x3F, "REGISTER", registerPayload(), true), "register");
        assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                customPayload(0x3F, "FML|HS", bytes(0, 2, 0, 0, 0, 0), true), "server_hello");
        assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                customPayload(0x17, "REGISTER", registerPayload(), false), "register");
        assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                customPayload(0x17, "FML|HS", bytes(1, 2), false), "client_hello");
        assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                customPayload(0x17, "FML|HS", modListPayload(), false), "mod_list");
        assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                customPayload(0x3F, "FML|HS", modListPayload(), true), "mod_list");
        assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                customPayload(0x17, "FML|HS", bytes(255, 2), false), "handshake_ack");
        assertEquals(MinecraftForgeHandshakeTracker.Stage.WAITING_SERVER_DATA, tracker.stage());
    }

    static void advanceToComplete(MinecraftForgeHandshakeTracker tracker) {
        assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                customPayload(0x3F, "FML|HS", bytes(0, 2, 0, 0, 0, 0), true), "server_hello");
        assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                customPayload(0x17, "REGISTER", registerPayload(), false), "register");
        assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                customPayload(0x17, "FML|HS", bytes(1, 2), false), "client_hello");
        assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                customPayload(0x17, "FML|HS", modListPayload(), false), "mod_list");
        assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                customPayload(0x3F, "FML|HS", modListPayload(), true), "mod_list");
        assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                customPayload(0x17, "FML|HS", bytes(255, 2), false), "handshake_ack");
        assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                customPayload(0x3F, "FML|HS", bytes(3, 0), true), "registry_data");
        assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                customPayload(0x17, "FML|HS", bytes(255, 3), false), "handshake_ack");
        assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                customPayload(0x3F, "FML|HS", bytes(255, 2), true), "handshake_ack");
        assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                customPayload(0x17, "FML|HS", bytes(255, 4), false), "handshake_ack");
        assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND,
                customPayload(0x3F, "FML|HS", bytes(255, 3), true), "handshake_ack");
        assertEvent(tracker, MinecraftForgeHandshakeTracker.Direction.SERVERBOUND,
                customPayload(0x17, "FML|HS", bytes(255, 5), false), "handshake_ack");
    }

    private static void assertEvent(
            MinecraftForgeHandshakeTracker tracker,
            MinecraftForgeHandshakeTracker.Direction direction,
            ByteBuf frame,
            String type) {
        try {
            var events = tracker.observe(direction, frame);
            assertEquals(1, events.size());
            assertEquals(type, events.get(0).type());
            assertTrue(events.get(0).expected(), events.get(0).type() + " should be in the expected order");
        } finally {
            frame.release();
        }
    }

    private static void assertCompressedEvent(
            MinecraftForgeHandshakeTracker tracker,
            MinecraftForgeHandshakeTracker.Direction direction,
            ByteBuf frame,
            String type) {
        try {
            var events = tracker.observeCompressed(direction, UnpooledByteBufAllocator.DEFAULT, frame, 0);
            assertEquals(1, events.size());
            assertEquals(type, events.get(0).type());
            assertTrue(events.get(0).expected(), events.get(0).type() + " should be in the expected order");
        } finally {
            frame.release();
        }
    }

    private static void assertUnexpectedEvent(
            MinecraftForgeHandshakeTracker tracker,
            MinecraftForgeHandshakeTracker.Direction direction,
            ByteBuf frame,
            String type) {
        try {
            var events = tracker.observe(direction, frame);
            assertEquals(1, events.size());
            assertEquals(type, events.get(0).type());
            assertFalse(events.get(0).expected(), events.get(0).type() + " should be rejected as out of order");
        } finally {
            frame.release();
        }
    }

    private static void assertEvent(
            MinecraftForgeHandshakeTracker tracker,
            MinecraftForgeHandshakeTracker.Direction direction,
            ByteBuf frame,
            String firstType,
            String secondType) {
        try {
            var events = tracker.observe(direction, frame);
            assertEquals(2, events.size());
            assertEquals(firstType, events.get(0).type());
            assertTrue(events.get(0).expected(), events.get(0).type() + " should be in the expected order");
            assertEquals(secondType, events.get(1).type());
            assertTrue(events.get(1).expected(), events.get(1).type() + " should be in the expected order");
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
            var frame = Unpooled.buffer();
            MinecraftVarInts.write(frame, packet.readableBytes());
            frame.writeBytes(packet, packet.readerIndex(), packet.readableBytes());
            return frame;
        } finally {
            payload.release();
            packet.release();
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
        return registerPayload(registerPayloadString());
    }

    private static String registerPayloadString() {
        return "FML|HS\0FML\0FML|MP\0FORGE";
    }

    private static ByteBuf registerPayload(String channels) {
        return Unpooled.wrappedBuffer(channels.getBytes(StandardCharsets.UTF_8));
    }

    private static ByteBuf modListPayload() {
        var payload = Unpooled.buffer();
        payload.writeByte(2);
        MinecraftVarInts.write(payload, 1);
        writeString(payload, "mcp");
        writeString(payload, "9.05");
        return payload;
    }

    private static ByteBuf multipartStartPayload(String wrappedChannel, int parts, int totalLength) {
        var payload = Unpooled.buffer();
        writeString(payload, wrappedChannel);
        payload.writeByte(parts);
        payload.writeInt(totalLength);
        return payload;
    }

    private static ByteBuf multipartPartPayload(int part, int... values) {
        var payload = Unpooled.buffer(values.length + 1);
        payload.writeByte(part);
        for (var value : values) {
            payload.writeByte(value);
        }
        return payload;
    }

    private static ByteBuf bytes(int... values) {
        var payload = Unpooled.buffer(values.length);
        for (var value : values) {
            payload.writeByte(value);
        }
        return payload;
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

    private static void assertForgeReset(ByteBuf frame) {
        var payload = payload(frame);
        try {
            assertEquals(0x3F, MinecraftVarInts.read(payload));
            assertEquals("FML|HS", readString(payload));
            assertEquals(2, readVarShort(payload));
            assertEquals(0xFE, payload.readUnsignedByte());
            assertEquals(0, payload.readUnsignedByte());
        } finally {
            payload.release();
        }
    }

    private static void assertCompressedForgeReset(MinecraftCompressionCodec codec, ByteBuf frame, int threshold) {
        var payload = codec.decodeFrame(UnpooledByteBufAllocator.DEFAULT, frame, threshold, 4096);
        try {
            assertEquals(0x3F, MinecraftVarInts.read(payload));
            assertEquals("FML|HS", readString(payload));
            assertEquals(2, payload.readableBytes());
            assertEquals(0xFE, payload.readUnsignedByte());
            assertEquals(0, payload.readUnsignedByte());
        } finally {
            payload.release();
        }
    }

    private static void assertCompressedCustomPayload18(
            MinecraftCompressionCodec codec,
            ByteBuf frame,
            int threshold,
            int packetId,
            String channel,
            String payloadText) {
        var payload = codec.decodeFrame(UnpooledByteBufAllocator.DEFAULT, frame, threshold, 4096);
        try {
            assertEquals(packetId, MinecraftVarInts.read(payload));
            assertEquals(channel, readString(payload));
            assertEquals(payloadText, payload.readCharSequence(payload.readableBytes(), StandardCharsets.UTF_8).toString());
            assertEquals(0, payload.readableBytes());
        } finally {
            payload.release();
        }
    }

    private static int packetId(ByteBuf frame) {
        var payload = payload(frame);
        try {
            return MinecraftVarInts.read(payload);
        } finally {
            payload.release();
        }
    }

    private static ByteBuf payload(ByteBuf frame) {
        var view = frame.retainedDuplicate();
        try {
            var length = MinecraftVarInts.read(view);
            return view.readRetainedSlice(length);
        } finally {
            view.release();
        }
    }

    private static String readString(ByteBuf input) {
        var length = MinecraftVarInts.read(input);
        var value = input.toString(input.readerIndex(), length, StandardCharsets.UTF_8);
        input.skipBytes(length);
        return value;
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

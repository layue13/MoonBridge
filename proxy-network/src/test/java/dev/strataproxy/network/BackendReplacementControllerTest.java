package dev.strataproxy.network;

import dev.strataproxy.codec.minecraft.MinecraftCompressionCodec;
import dev.strataproxy.analysis.CustomPayloadAnomalyPolicy;
import dev.strataproxy.api.server.ProtocolRange;
import dev.strataproxy.api.server.RegisteredServer;
import dev.strataproxy.api.server.ServerDescriptor;
import dev.strataproxy.api.server.ServerHealth;
import dev.strataproxy.api.server.ServerLoad;
import dev.strataproxy.observability.ProxyMetrics;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BackendReplacementControllerTest {
    @Test
    void defersTransferWhileLegacyForgeHandshakeIsIncomplete() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var metrics = new ProxyMetrics();
        var session = new RelaySession("127.0.0.1:50000");
        var frontend = new EmbeddedChannel();
        var backend = new EmbeddedChannel();
        session.attach(frontend, backend, "lobby-1");
        var tracker = session.startForgeHandshakeTracker(4096, profile);
        var register = customPayload(0x3F, "REGISTER", registerPayload(), true);
        try {
            tracker.observe(MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND, register);
        } finally {
            register.release();
        }

        var controller = new BackendReplacementController(
                name -> Optional.of(server(name)),
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

        var result = controller.replaceBackend("survival-1").toCompletableFuture();
        frontend.runPendingTasks();

        assertFalse(result.isDone());
        assertEquals(1, metrics.snapshot().backendReplacements().get("forge_handshake_deferred"));
        var pending = session.pendingBackendTransfer();
        assertNotNull(pending);
        assertEquals("survival-1", pending.targetServerName());

        frontend.finishAndReleaseAll();
        backend.finishAndReleaseAll();
        tracker.close();
    }

    @Test
    void refusesSecondTransferWhileForgeDeferredTransferIsPending() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var metrics = new ProxyMetrics();
        var session = new RelaySession("127.0.0.1:50000");
        var frontend = new EmbeddedChannel();
        var backend = new EmbeddedChannel();
        session.attach(frontend, backend, "lobby-1");
        var tracker = session.startForgeHandshakeTracker(4096, profile);
        var register = customPayload(0x3F, "REGISTER", registerPayload(), true);
        try {
            tracker.observe(MinecraftForgeHandshakeTracker.Direction.CLIENTBOUND, register);
        } finally {
            register.release();
        }

        var controller = new BackendReplacementController(
                name -> Optional.of(server(name)),
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

        var first = controller.replaceBackend("survival-1").toCompletableFuture();
        frontend.runPendingTasks();
        var second = controller.replaceBackend("minigame-1").toCompletableFuture();
        frontend.runPendingTasks();

        assertFalse(first.isDone());
        assertFalse(second.join().success());
        assertEquals("forge_handshake_transfer_pending", second.join().outcome());
        assertEquals("survival-1", session.pendingBackendTransfer().targetServerName());

        frontend.finishAndReleaseAll();
        backend.finishAndReleaseAll();
        tracker.close();
    }

    @Test
    void doesNotDeferTransferForClientOnlyForgeChannelRegistration() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var metrics = new ProxyMetrics();
        var session = new RelaySession("127.0.0.1:50000");
        var frontend = new EmbeddedChannel();
        var backend = new EmbeddedChannel();
        session.attach(frontend, backend, "lobby-1");
        var tracker = session.startForgeHandshakeTracker(4096, profile);
        var register = customPayload(0x17, "REGISTER", registerPayload(), false);
        try {
            tracker.observe(MinecraftForgeHandshakeTracker.Direction.SERVERBOUND, register);
        } finally {
            register.release();
        }

        var controller = new BackendReplacementController(
                name -> Optional.of(server(name)),
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

        var result = controller.replaceBackend("survival-1").toCompletableFuture();
        frontend.runPendingTasks();

        assertFalse(result.join().success());
        assertEquals("missing_login_session", result.join().outcome());
        assertNull(session.pendingBackendTransfer());
        assertNull(metrics.snapshot().backendReplacements().get("forge_handshake_deferred"));

        frontend.finishAndReleaseAll();
        backend.finishAndReleaseAll();
        tracker.close();
    }

    @Test
    void rejectsBackendTransferWhenTargetDoesNotAcceptClientProtocol() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var metrics = new ProxyMetrics();
        var session = new RelaySession("127.0.0.1:50000");
        var frontend = new EmbeddedChannel();
        var backend = new EmbeddedChannel();
        session.attach(frontend, backend, "lobby-1");

        var controller = new BackendReplacementController(
                new ServerTargetResolver() {
                    @Override
                    public Optional<RegisteredServer> resolveTarget(String serverName) {
                        return Optional.of(server(serverName, new ProtocolRange(763, 763, "1.20.1")));
                    }

                    @Override
                    public Optional<RegisteredServer> resolveTarget(String serverName, int protocolVersion) {
                        return resolveTarget(serverName)
                                .filter(server -> server.descriptor().protocolRange().accepts(protocolVersion));
                    }
                },
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

        var result = controller.replaceBackend("modern-1").toCompletableFuture();
        frontend.runPendingTasks();

        assertFalse(result.join().success());
        assertEquals("target_unavailable", result.join().outcome());
        var transfer = metrics.snapshot().recentPlayerTransfers().getFirst();
        assertEquals("target_unavailable", transfer.outcome());
        assertEquals("modern-1", transfer.targetServer());

        frontend.finishAndReleaseAll();
        backend.finishAndReleaseAll();
    }

    @Test
    void rejectsBackendTransferWhenProtocolProfileIsUnknown() {
        var profile = MinecraftProtocolProfile.forVersion(9999);
        var metrics = new ProxyMetrics();
        var session = new RelaySession("127.0.0.1:50000");
        var frontend = new EmbeddedChannel();
        var backend = new EmbeddedChannel();
        session.attach(frontend, backend, "unknown-a");

        var controller = new BackendReplacementController(
                name -> Optional.of(server(name, new ProtocolRange(9999, 9999, "9999"))),
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

        var result = controller.replaceBackend("unknown-b").toCompletableFuture();
        frontend.runPendingTasks();

        assertFalse(result.join().success());
        assertEquals("unsupported_protocol_switch", result.join().outcome());
        assertEquals(1, metrics.snapshot().backendReplacements().get("unsupported_protocol_switch"));
        var transfer = metrics.snapshot().recentPlayerTransfers().getFirst();
        assertEquals("unsupported_protocol_switch", transfer.outcome());
        assertEquals("unknown-b", transfer.targetServer());

        frontend.finishAndReleaseAll();
        backend.finishAndReleaseAll();
    }

    @Test
    void prependsForgeResetWhenLeavingCompletedLegacyForgeServer() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var session = new RelaySession("127.0.0.1:50000");
        var frontend = new EmbeddedChannel();
        var controller = new BackendReplacementController(
                name -> Optional.of(server(name)),
                null,
                new ProxyMetrics(),
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
        var original = frame(packet(0x08));

        var frames = controller.prependForgeResetIfNeeded(frontend, original, true);

        try {
            assertForgeReset(frames);
            var payload = payload(frames);
            try {
                assertEquals(0x08, dev.strataproxy.codec.minecraft.MinecraftVarInts.read(payload));
            } finally {
                payload.release();
            }
            assertEquals(0, frames.readableBytes());
        } finally {
            release(frames);
            frontend.finishAndReleaseAll();
        }
    }

    @Test
    void prependsCompressedForgeResetWhenLeavingCompleted18ForgeServer() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_8);
        var session = new RelaySession("127.0.0.1:50000");
        var frontend = new EmbeddedChannel();
        var controller = new BackendReplacementController(
                name -> Optional.of(server(name, new ProtocolRange(47, 47, "47"))),
                null,
                new ProxyMetrics(),
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
        var original = compressedFrame(packet(0x08), 0);

        var frames = controller.prependForgeResetIfNeeded(frontend, original, true, compression);

        try (var codec = new MinecraftCompressionCodec()) {
            assertCompressedForgeReset(codec, frames, 0);
            var payload = codec.decodeFrame(UnpooledByteBufAllocator.DEFAULT, frames, 0, 4096);
            try {
                assertEquals(0x08, dev.strataproxy.codec.minecraft.MinecraftVarInts.read(payload));
                assertEquals(0, payload.readableBytes());
            } finally {
                payload.release();
            }
            assertEquals(0, frames.readableBytes());
        } finally {
            release(frames);
            frontend.finishAndReleaseAll();
        }
    }

    @Test
    void sendsForgeResetBeforeProcessingRemainingBackendFramesDuringSwitch() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var metrics = new ProxyMetrics();
        var session = new RelaySession("127.0.0.1:50000");
        session.identity().playerName("Steve");
        var frontend = new EmbeddedChannel();
        var oldBackend = new EmbeddedChannel();
        var nextBackend = new EmbeddedChannel();
        session.attach(frontend, oldBackend, "lobby-1");
        var controller = new BackendReplacementController(
                name -> Optional.of(server(name)),
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
        var swap = session.beginForgeHandshakeTrackerSwap(4096, profile);
        var result = new CompletableFuture<PlayerTransferResult>();

        controller.completeReplacement(
                frontend,
                oldBackend,
                "lobby-1",
                "forge-1",
                nextBackend,
                new MinecraftCompressionAuditState(4096),
                null,
                customPayload(0x3F, "REGISTER", registerPayload(), true),
                true,
                swap,
                result);
        frontend.runPendingTasks();
        nextBackend.runPendingTasks();

        var reset = (ByteBuf) frontend.readOutbound();
        var register = (ByteBuf) frontend.readOutbound();
        try {
            assertForgeReset(reset);
            assertRegister(register);
            assertTrue(result.join().success());
        } finally {
            release(reset);
            release(register);
            frontend.finishAndReleaseAll();
            oldBackend.finishAndReleaseAll();
            nextBackend.finishAndReleaseAll();
            session.forgeHandshakeTracker().close();
        }
    }

    @Test
    void clearsLegacyClientStateBeforeProcessingNewBackendFramesDuringSwitch() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var metrics = new ProxyMetrics();
        var session = new RelaySession("127.0.0.1:50000");
        session.identity().playerName("Steve");
        var frontend = new EmbeddedChannel();
        var oldBackend = new EmbeddedChannel();
        var nextBackend = new EmbeddedChannel();
        session.attach(frontend, oldBackend, "lobby-1");
        var controller = new BackendReplacementController(
                name -> Optional.of(server(name)),
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
        var state = combine(playerList1710("Alex", true), objective1710("sidebar", 0), team("red", 0));
        try {
            controller.observeLegacyClientState(state, profile);
        } finally {
            state.release();
        }
        var swap = session.beginForgeHandshakeTrackerSwap(4096, profile);
        var result = new CompletableFuture<PlayerTransferResult>();

        controller.completeReplacement(
                frontend,
                oldBackend,
                "lobby-1",
                "survival-1",
                nextBackend,
                new MinecraftCompressionAuditState(4096),
                null,
                frame(packet(0x08)),
                false,
                swap,
                result);
        frontend.runPendingTasks();
        nextBackend.runPendingTasks();

        var cleanup = (ByteBuf) frontend.readOutbound();
        var remaining = (ByteBuf) frontend.readOutbound();
        try {
            assertPlayerListRemove1710(cleanup);
            assertObjectiveRemove1710(cleanup);
            assertTeamRemove(cleanup);
            assertEquals(0, cleanup.readableBytes());
            var payload = payload(remaining);
            try {
                assertEquals(0x08, dev.strataproxy.codec.minecraft.MinecraftVarInts.read(payload));
            } finally {
                payload.release();
            }
            assertTrue(result.join().success());
        } finally {
            release(cleanup);
            release(remaining);
            frontend.finishAndReleaseAll();
            oldBackend.finishAndReleaseAll();
            nextBackend.finishAndReleaseAll();
            session.forgeHandshakeTracker().close();
        }
    }

    @Test
    void compressesLegacyClientStateResetBeforeProcessingCompressedNewBackendFramesDuringSwitch() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_8);
        var metrics = new ProxyMetrics();
        var session = new RelaySession("127.0.0.1:50000");
        session.identity().playerName("Steve");
        var frontend = new EmbeddedChannel();
        var oldBackend = new EmbeddedChannel();
        var nextBackend = new EmbeddedChannel();
        session.attach(frontend, oldBackend, "lobby-1");
        var controller = new BackendReplacementController(
                name -> Optional.of(server(name, new ProtocolRange(47, 47, "47"))),
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
        var state = objective1710("sidebar", 0);
        try {
            controller.observeLegacyClientState(state, profile);
        } finally {
            state.release();
        }
        var swap = session.beginForgeHandshakeTrackerSwap(4096, profile);
        var compression = new MinecraftCompressionAuditState(4096);
        compression.negotiate(0);
        var result = new CompletableFuture<PlayerTransferResult>();

        controller.completeReplacement(
                frontend,
                oldBackend,
                "lobby-1",
                "survival-1",
                nextBackend,
                compression,
                null,
                compressedFrame(packet(0x08), 0),
                false,
                swap,
                result);
        frontend.runPendingTasks();
        nextBackend.runPendingTasks();

        var cleanup = (ByteBuf) frontend.readOutbound();
        var remainingFrame = (ByteBuf) frontend.readOutbound();
        try (var codec = new MinecraftCompressionCodec()) {
            assertCompressedObjectiveRemove(codec, cleanup, 0, "sidebar");
            assertEquals(0, cleanup.readableBytes());
            var remaining = codec.decodeFrame(UnpooledByteBufAllocator.DEFAULT, remainingFrame, 0, 4096);
            try {
                assertEquals(0x08, dev.strataproxy.codec.minecraft.MinecraftVarInts.read(remaining));
                assertEquals(0, remaining.readableBytes());
            } finally {
                remaining.release();
            }
            assertEquals(0, remainingFrame.readableBytes());
            assertTrue(result.join().success());
        } finally {
            release(cleanup);
            release(remainingFrame);
            frontend.finishAndReleaseAll();
            oldBackend.finishAndReleaseAll();
            nextBackend.finishAndReleaseAll();
            session.forgeHandshakeTracker().close();
        }
    }

    @Test
    void closesSessionWhenInitialClientboundWriteFailsAfterRelaySwap() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var metrics = new ProxyMetrics();
        var session = new RelaySession("127.0.0.1:50000");
        session.identity().playerName("Steve");
        var frontend = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                ReferenceCountUtil.release(message);
                promise.setFailure(new IOException("client write failed"));
            }
        });
        var oldBackend = new EmbeddedChannel();
        var nextBackend = new EmbeddedChannel();
        session.attach(frontend, oldBackend, "lobby-1");
        var controller = new BackendReplacementController(
                name -> Optional.of(server(name)),
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
        var swap = session.beginForgeHandshakeTrackerSwap(4096, profile);
        var result = new CompletableFuture<PlayerTransferResult>();

        controller.completeReplacement(
                frontend,
                oldBackend,
                "lobby-1",
                "survival-1",
                nextBackend,
                new MinecraftCompressionAuditState(4096),
                frame(packet(0x08)),
                null,
                false,
                swap,
                result);
        frontend.runPendingTasks();
        nextBackend.runPendingTasks();
        oldBackend.runPendingTasks();

        assertFalse(result.join().success());
        assertEquals("initial_clientbound_write_failure", result.join().outcome());
        assertFalse(frontend.isOpen());
        assertFalse(oldBackend.isOpen());
        assertFalse(nextBackend.isOpen());
        assertEquals(1, metrics.snapshot().backendReplacements().get("initial_clientbound_write_failure"));

        frontend.finishAndReleaseAll();
        oldBackend.finishAndReleaseAll();
        nextBackend.finishAndReleaseAll();
    }

    @Test
    void usesForgeSafeSwitchAfterLegacyForgeClientIsDetectedAtRuntime() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var metrics = new ProxyMetrics();
        var session = new RelaySession("127.0.0.1:50000");
        session.identity().playerName("Steve");
        session.legacyForgeClientDetected(true);
        var frontend = new EmbeddedChannel();
        var oldBackend = new EmbeddedChannel();
        var nextBackend = new EmbeddedChannel();
        session.attach(frontend, oldBackend, "lobby-1");
        var controller = new BackendReplacementController(
                name -> Optional.of(server(name)),
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
        var swap = session.beginForgeHandshakeTrackerSwap(4096, profile);
        var result = new CompletableFuture<PlayerTransferResult>();

        controller.completeReplacement(
                frontend,
                oldBackend,
                "lobby-1",
                "forge-1",
                nextBackend,
                new MinecraftCompressionAuditState(4096),
                null,
                joinGameForge1710(12, 0, 0, 2, "default"),
                false,
                swap,
                result);
        frontend.runPendingTasks();
        nextBackend.runPendingTasks();

        var frames = (ByteBuf) frontend.readOutbound();
        try {
            assertJoinGameForge1710(frames, 12, 0, 0, 2, "default");
            assertRespawn1710(frames, -1, 2, 0, "default");
            assertRespawn1710(frames, 0, 2, 0, "default");
            assertEquals(0, frames.readableBytes());
            assertTrue(result.join().success());
        } finally {
            release(frames);
            frontend.finishAndReleaseAll();
            oldBackend.finishAndReleaseAll();
            nextBackend.finishAndReleaseAll();
            session.forgeHandshakeTracker().close();
        }
    }

    @Test
    void replacementParsesForge1710JoinGameWithIntDimensionDuringSafeSwitch() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var metrics = new ProxyMetrics();
        var session = new RelaySession("127.0.0.1:50000");
        session.identity().playerName("Steve");
        session.legacyForgeClientDetected(true);
        var frontend = new EmbeddedChannel();
        var oldBackend = new EmbeddedChannel();
        var nextBackend = new EmbeddedChannel();
        session.attach(frontend, oldBackend, "lobby-1");
        var controller = new BackendReplacementController(
                name -> Optional.of(server(name)),
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
        var swap = session.beginForgeHandshakeTrackerSwap(4096, profile);
        var result = new CompletableFuture<PlayerTransferResult>();

        controller.completeReplacement(
                frontend,
                oldBackend,
                "lobby-1",
                "forge-1",
                nextBackend,
                new MinecraftCompressionAuditState(4096),
                null,
                joinGameForge1710(12, 0, 300, 2, "default"),
                false,
                swap,
                result);
        frontend.runPendingTasks();
        nextBackend.runPendingTasks();

        var frames = (ByteBuf) frontend.readOutbound();
        try {
            assertJoinGameForge1710(frames, 12, 0, 300, 2, "default");
            assertRespawn1710(frames, -1, 2, 0, "default");
            assertRespawn1710(frames, 300, 2, 0, "default");
            assertEquals(0, frames.readableBytes());
            assertTrue(result.join().success());
        } finally {
            release(frames);
            frontend.finishAndReleaseAll();
            oldBackend.finishAndReleaseAll();
            nextBackend.finishAndReleaseAll();
            session.forgeHandshakeTracker().close();
        }
    }

    @Test
    void replaysClientPluginChannelsToNewBackendAfterLegacySwitchJoinGameFlushes() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var metrics = new ProxyMetrics();
        var session = new RelaySession("127.0.0.1:50000");
        session.identity().playerName("Steve");
        session.legacyForgeClientDetected(true);
        var clientRegister = customPayload(0x17, "REGISTER", registerPayload(), false);
        try {
            session.pluginChannelRegistry().observeServerbound(clientRegister, profile);
        } finally {
            clientRegister.release();
        }
        var frontend = new EmbeddedChannel();
        var oldBackend = new EmbeddedChannel();
        var nextBackend = new EmbeddedChannel();
        session.attach(frontend, oldBackend, "lobby-1");
        var controller = new BackendReplacementController(
                name -> Optional.of(server(name)),
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
        var swap = session.beginForgeHandshakeTrackerSwap(4096, profile);
        var result = new CompletableFuture<PlayerTransferResult>();

        controller.completeReplacement(
                frontend,
                oldBackend,
                "lobby-1",
                "forge-1",
                nextBackend,
                new MinecraftCompressionAuditState(4096),
                null,
                joinGameForge1710(12, 0, 0, 2, "default"),
                false,
                swap,
                result);
        frontend.runPendingTasks();
        nextBackend.runPendingTasks();

        var switchFrames = (ByteBuf) frontend.readOutbound();
        var replayedRegister = (ByteBuf) nextBackend.readOutbound();
        try {
            assertJoinGameForge1710(switchFrames, 12, 0, 0, 2, "default");
            assertRespawn1710(switchFrames, -1, 2, 0, "default");
            assertRespawn1710(switchFrames, 0, 2, 0, "default");
            assertEquals(0, switchFrames.readableBytes());
            assertServerboundRegister(replayedRegister, "FML|HS\0FML\0FML|MP\0FORGE");
            assertTrue(result.join().success());
        } finally {
            release(switchFrames);
            release(replayedRegister);
            frontend.finishAndReleaseAll();
            oldBackend.finishAndReleaseAll();
            nextBackend.finishAndReleaseAll();
            session.forgeHandshakeTracker().close();
            session.closePluginChannelRegistry();
        }
    }

    @Test
    void convertsCompressedLegacySwitchJoinGameFor18ForgeClient() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_8);
        var metrics = new ProxyMetrics();
        var session = new RelaySession("127.0.0.1:50000");
        session.identity().playerName("Steve");
        session.legacyForgeClientDetected(true);
        var frontend = new EmbeddedChannel();
        var oldBackend = new EmbeddedChannel();
        var nextBackend = new EmbeddedChannel();
        session.attach(frontend, oldBackend, "lobby-1");
        var controller = new BackendReplacementController(
                name -> Optional.of(server(name, new ProtocolRange(47, 47, "47"))),
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
        var swap = session.beginForgeHandshakeTrackerSwap(4096, profile);
        var compression = new MinecraftCompressionAuditState(4096);
        compression.negotiate(0);
        var result = new CompletableFuture<PlayerTransferResult>();

        controller.completeReplacement(
                frontend,
                oldBackend,
                "lobby-1",
                "forge-1",
                nextBackend,
                compression,
                null,
                compressedJoinGame1710(12, 0, 0, 2, "default", 0),
                false,
                swap,
                result);
        frontend.runPendingTasks();
        nextBackend.runPendingTasks();

        var frames = (ByteBuf) frontend.readOutbound();
        try (var codec = new MinecraftCompressionCodec()) {
            assertCompressedJoinGame1710(codec, frames, 0, 12, 0, 0, 2, "default");
            assertCompressedRespawn1710(codec, frames, 0, -1, 2, 0, "default");
            assertCompressedRespawn1710(codec, frames, 0, 0, 2, 0, "default");
            assertEquals(0, frames.readableBytes());
            assertTrue(result.join().success());
        } finally {
            release(frames);
            frontend.finishAndReleaseAll();
            oldBackend.finishAndReleaseAll();
            nextBackend.finishAndReleaseAll();
            session.forgeHandshakeTracker().close();
        }
    }

    @Test
    void replaysCompressedClientPluginChannelsToNew18BackendAfterSwitchJoinGameFlushes() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_8);
        var metrics = new ProxyMetrics();
        var session = new RelaySession("127.0.0.1:50000");
        session.identity().playerName("Steve");
        session.legacyForgeClientDetected(true);
        var clientRegister = customPayloadRemaining(0x17, "REGISTER", registerPayload());
        try {
            session.pluginChannelRegistry().observeServerbound(clientRegister, profile);
        } finally {
            clientRegister.release();
        }
        var frontend = new EmbeddedChannel();
        var oldBackend = new EmbeddedChannel();
        var nextBackend = new EmbeddedChannel();
        session.attach(frontend, oldBackend, "lobby-1");
        var controller = new BackendReplacementController(
                name -> Optional.of(server(name, new ProtocolRange(47, 47, "47"))),
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
        var swap = session.beginForgeHandshakeTrackerSwap(4096, profile);
        var compression = new MinecraftCompressionAuditState(4096);
        compression.negotiate(0);
        var result = new CompletableFuture<PlayerTransferResult>();

        controller.completeReplacement(
                frontend,
                oldBackend,
                "lobby-1",
                "forge-1",
                nextBackend,
                compression,
                null,
                compressedJoinGame1710(12, 0, 0, 2, "default", 0),
                false,
                swap,
                result);
        frontend.runPendingTasks();
        nextBackend.runPendingTasks();

        var switchFrames = (ByteBuf) frontend.readOutbound();
        var replayedRegister = (ByteBuf) nextBackend.readOutbound();
        try (var codec = new MinecraftCompressionCodec()) {
            assertCompressedJoinGame1710(codec, switchFrames, 0, 12, 0, 0, 2, "default");
            assertCompressedRespawn1710(codec, switchFrames, 0, -1, 2, 0, "default");
            assertCompressedRespawn1710(codec, switchFrames, 0, 0, 2, 0, "default");
            assertEquals(0, switchFrames.readableBytes());
            assertCompressedServerboundRegister(codec, replayedRegister, 0, "FML|HS\0FML\0FML|MP\0FORGE");
            assertEquals(0, replayedRegister.readableBytes());
            assertTrue(result.join().success());
        } finally {
            release(switchFrames);
            release(replayedRegister);
            frontend.finishAndReleaseAll();
            oldBackend.finishAndReleaseAll();
            nextBackend.finishAndReleaseAll();
            session.forgeHandshakeTracker().close();
            session.closePluginChannelRegistry();
        }
    }

    private static RegisteredServer server(String name) {
        return server(name, new ProtocolRange(5, 5, "5"));
    }

    private static RegisteredServer server(String name, ProtocolRange protocolRange) {
        var descriptor = new ServerDescriptor(
                name,
                new InetSocketAddress("127.0.0.1", 25566),
                Set.of(),
                Set.of(),
                protocolRange,
                100,
                100,
                120,
                false,
                Map.of());
        return new RegisteredServer() {
            @Override
            public ServerDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public ServerHealth health() {
                return ServerHealth.up(-1);
            }

            @Override
            public ServerLoad load() {
                return new ServerLoad(0, 100, 120, 0, 0, 0, 0);
            }

            @Override
            public boolean draining() {
                return false;
            }
        };
    }

    private static ByteBuf customPayload(int packetId, String channel, ByteBuf payload, boolean clientbound) {
        var packet = Unpooled.buffer();
        try {
            dev.strataproxy.codec.minecraft.MinecraftVarInts.write(packet, packetId);
            writeString(packet, channel);
            if (clientbound) {
                writeVarShort(packet, payload.readableBytes());
            } else {
                packet.writeShort(payload.readableBytes());
            }
            packet.writeBytes(payload, payload.readerIndex(), payload.readableBytes());
            var frame = Unpooled.buffer();
            dev.strataproxy.codec.minecraft.MinecraftVarInts.write(frame, packet.readableBytes());
            frame.writeBytes(packet, packet.readerIndex(), packet.readableBytes());
            return frame;
        } finally {
            payload.release();
            packet.release();
        }
    }

    private static ByteBuf customPayloadRemaining(int packetId, String channel, ByteBuf payload) {
        var packet = Unpooled.buffer();
        try {
            dev.strataproxy.codec.minecraft.MinecraftVarInts.write(packet, packetId);
            writeString(packet, channel);
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
        dev.strataproxy.codec.minecraft.MinecraftVarInts.write(frame, packet.readableBytes());
        frame.writeBytes(packet, packet.readerIndex(), packet.readableBytes());
        packet.release();
        return frame;
    }

    private static ByteBuf compressedFrame(ByteBuf packet, int threshold) {
        try (var codec = new MinecraftCompressionCodec()) {
            return codec.encodeFrame(UnpooledByteBufAllocator.DEFAULT, packet, threshold);
        } finally {
            packet.release();
        }
    }

    private static ByteBuf combine(ByteBuf... frames) {
        var output = Unpooled.buffer();
        for (var frame : frames) {
            output.writeBytes(frame, frame.readerIndex(), frame.readableBytes());
            frame.release();
        }
        return output;
    }

    private static ByteBuf packet(int packetId) {
        var packet = Unpooled.buffer();
        dev.strataproxy.codec.minecraft.MinecraftVarInts.write(packet, packetId);
        return packet;
    }

    private static ByteBuf objective1710(String name, int mode) {
        var packet = Unpooled.buffer();
        dev.strataproxy.codec.minecraft.MinecraftVarInts.write(packet, 0x3B);
        writeString(packet, name);
        packet.writeByte(mode);
        return frame(packet);
    }

    private static ByteBuf playerList1710(String name, boolean online) {
        var packet = Unpooled.buffer();
        dev.strataproxy.codec.minecraft.MinecraftVarInts.write(packet, 0x38);
        writeString(packet, name);
        packet.writeBoolean(online);
        packet.writeShort(42);
        return frame(packet);
    }

    private static ByteBuf team(String name, int mode) {
        var packet = Unpooled.buffer();
        dev.strataproxy.codec.minecraft.MinecraftVarInts.write(packet, 0x3E);
        writeString(packet, name);
        packet.writeByte(mode);
        return frame(packet);
    }

    private static ByteBuf joinGame1710(int entityId, int gameMode, int dimension, int difficulty, String levelType) {
        var packet = Unpooled.buffer();
        dev.strataproxy.codec.minecraft.MinecraftVarInts.write(packet, 0x01);
        packet.writeInt(entityId);
        packet.writeByte(gameMode);
        packet.writeByte(dimension);
        packet.writeByte(difficulty);
        packet.writeByte(20);
        writeString(packet, levelType);
        return frame(packet);
    }

    private static ByteBuf joinGameForge1710(int entityId, int gameMode, int dimension, int difficulty, String levelType) {
        var packet = Unpooled.buffer();
        dev.strataproxy.codec.minecraft.MinecraftVarInts.write(packet, 0x01);
        packet.writeInt(entityId);
        packet.writeByte(gameMode);
        packet.writeInt(dimension);
        packet.writeByte(difficulty);
        packet.writeByte(20);
        writeString(packet, levelType);
        return frame(packet);
    }

    private static ByteBuf compressedJoinGame1710(
            int entityId,
            int gameMode,
            int dimension,
            int difficulty,
            String levelType,
            int threshold) {
        var packet = Unpooled.buffer();
        try (var codec = new MinecraftCompressionCodec()) {
            dev.strataproxy.codec.minecraft.MinecraftVarInts.write(packet, 0x01);
            packet.writeInt(entityId);
            packet.writeByte(gameMode);
            packet.writeByte(dimension);
            packet.writeByte(difficulty);
            packet.writeByte(20);
            writeString(packet, levelType);
            return codec.encodeFrame(UnpooledByteBufAllocator.DEFAULT, packet, threshold);
        } finally {
            packet.release();
        }
    }

    private static ByteBuf payload(ByteBuf frames) {
        var length = dev.strataproxy.codec.minecraft.MinecraftVarInts.read(frames);
        return frames.readRetainedSlice(length);
    }

    private static void assertForgeReset(ByteBuf frames) {
        var payload = payload(frames);
        try {
            assertEquals(0x3F, dev.strataproxy.codec.minecraft.MinecraftVarInts.read(payload));
            assertEquals("FML|HS", readString(payload));
            assertEquals(2, readVarShort(payload));
            assertEquals(0xFE, payload.readUnsignedByte());
            assertEquals(0, payload.readUnsignedByte());
            assertEquals(0, payload.readableBytes());
        } finally {
            payload.release();
        }
    }

    private static void assertCompressedForgeReset(MinecraftCompressionCodec codec, ByteBuf frames, int threshold) {
        var payload = codec.decodeFrame(UnpooledByteBufAllocator.DEFAULT, frames, threshold, 4096);
        try {
            assertEquals(0x3F, dev.strataproxy.codec.minecraft.MinecraftVarInts.read(payload));
            assertEquals("FML|HS", readString(payload));
            assertEquals(2, payload.readableBytes());
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
            assertEquals(0x3F, dev.strataproxy.codec.minecraft.MinecraftVarInts.read(payload));
            assertEquals("REGISTER", readString(payload));
            var length = readVarShort(payload);
            assertEquals("FML|HS\0FML\0FML|MP\0FORGE",
                    payload.readCharSequence(length, StandardCharsets.UTF_8).toString());
            assertEquals(0, payload.readableBytes());
        } finally {
            payload.release();
        }
    }

    private static void assertServerboundRegister(ByteBuf frames, String expectedChannels) {
        var payload = payload(frames);
        try {
            assertEquals(0x17, dev.strataproxy.codec.minecraft.MinecraftVarInts.read(payload));
            assertEquals("REGISTER", readString(payload));
            var length = payload.readUnsignedShort();
            assertEquals(expectedChannels,
                    payload.readCharSequence(length, StandardCharsets.UTF_8).toString());
            assertEquals(0, payload.readableBytes());
        } finally {
            payload.release();
        }
    }

    private static void assertCompressedServerboundRegister(
            MinecraftCompressionCodec codec,
            ByteBuf frames,
            int threshold,
            String expectedChannels) {
        var payload = codec.decodeFrame(UnpooledByteBufAllocator.DEFAULT, frames, threshold, 4096);
        try {
            assertEquals(0x17, dev.strataproxy.codec.minecraft.MinecraftVarInts.read(payload));
            assertEquals("REGISTER", readString(payload));
            assertEquals(expectedChannels,
                    payload.readCharSequence(payload.readableBytes(), StandardCharsets.UTF_8).toString());
            assertEquals(0, payload.readableBytes());
        } finally {
            payload.release();
        }
    }

    private static void assertObjectiveRemove1710(ByteBuf frames) {
        var payload = payload(frames);
        try {
            assertEquals(0x3B, dev.strataproxy.codec.minecraft.MinecraftVarInts.read(payload));
            assertEquals("sidebar", readString(payload));
            assertEquals(1, payload.readUnsignedByte());
            assertEquals(0, payload.readableBytes());
        } finally {
            payload.release();
        }
    }

    private static void assertCompressedObjectiveRemove(
            MinecraftCompressionCodec codec,
            ByteBuf frames,
            int threshold,
            String name) {
        var payload = codec.decodeFrame(UnpooledByteBufAllocator.DEFAULT, frames, threshold, 4096);
        try {
            assertEquals(0x3B, dev.strataproxy.codec.minecraft.MinecraftVarInts.read(payload));
            assertEquals(name, readString(payload));
            assertEquals(1, payload.readUnsignedByte());
            assertEquals(0, payload.readableBytes());
        } finally {
            payload.release();
        }
    }

    private static void assertPlayerListRemove1710(ByteBuf frames) {
        var payload = payload(frames);
        try {
            assertEquals(0x38, dev.strataproxy.codec.minecraft.MinecraftVarInts.read(payload));
            assertEquals("Alex", readString(payload));
            assertEquals(0, payload.readUnsignedByte());
            assertEquals(0, payload.readShort());
        } finally {
            payload.release();
        }
    }

    private static void assertTeamRemove(ByteBuf frames) {
        var payload = payload(frames);
        try {
            assertEquals(0x3E, dev.strataproxy.codec.minecraft.MinecraftVarInts.read(payload));
            assertEquals("red", readString(payload));
            assertEquals(1, payload.readUnsignedByte());
        } finally {
            payload.release();
        }
    }

    private static void assertJoinGame1710(
            ByteBuf frames,
            int entityId,
            int gameMode,
            int dimension,
            int difficulty,
            String levelType) {
        var payload = payload(frames);
        try {
            assertEquals(0x01, dev.strataproxy.codec.minecraft.MinecraftVarInts.read(payload));
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
            assertEquals(0x01, dev.strataproxy.codec.minecraft.MinecraftVarInts.read(payload));
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

    private static void assertRespawn1710(ByteBuf frames, int dimension, int difficulty, int gameMode, String levelType) {
        var payload = payload(frames);
        try {
            assertEquals(0x07, dev.strataproxy.codec.minecraft.MinecraftVarInts.read(payload));
            assertEquals(dimension, payload.readInt());
            assertEquals(difficulty, payload.readUnsignedByte());
            assertEquals(gameMode, payload.readUnsignedByte());
            assertEquals(levelType, readString(payload));
            assertEquals(0, payload.readableBytes());
        } finally {
            payload.release();
        }
    }

    private static void assertCompressedJoinGame1710(
            MinecraftCompressionCodec codec,
            ByteBuf frames,
            int threshold,
            int entityId,
            int gameMode,
            int dimension,
            int difficulty,
            String levelType) {
        var payload = codec.decodeFrame(UnpooledByteBufAllocator.DEFAULT, frames, threshold, 4096);
        try {
            assertEquals(0x01, dev.strataproxy.codec.minecraft.MinecraftVarInts.read(payload));
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

    private static void assertCompressedRespawn1710(
            MinecraftCompressionCodec codec,
            ByteBuf frames,
            int threshold,
            int dimension,
            int difficulty,
            int gameMode,
            String levelType) {
        var payload = codec.decodeFrame(UnpooledByteBufAllocator.DEFAULT, frames, threshold, 4096);
        try {
            assertEquals(0x07, dev.strataproxy.codec.minecraft.MinecraftVarInts.read(payload));
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
        dev.strataproxy.codec.minecraft.MinecraftVarInts.write(output, bytes.length);
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
        var bytes = new byte[dev.strataproxy.codec.minecraft.MinecraftVarInts.read(input)];
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

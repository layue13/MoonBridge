package dev.strataproxy.network;

import dev.strataproxy.analysis.CustomPayloadAnomalyPolicy;
import dev.strataproxy.codec.minecraft.MinecraftVarInts;
import dev.strataproxy.codec.minecraft.MinecraftCompressionCodec;
import dev.strataproxy.compression.FixedCompressionStrategy;
import dev.strataproxy.observability.ProxyMetrics;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RelayTrafficMetricsTest {
    @Test
    void attributesFrontendTrafficToSelectedServer() {
        var metrics = new ProxyMetrics();
        var backend = new EmbeddedChannel();
        var frontend = new EmbeddedChannel(new FrontendRelayHandler(backend, metrics, "survival-1"));

        frontend.writeInbound(Unpooled.wrappedBuffer(new byte[] {1, 2, 3, 4}));

        releaseOutbound(backend);
        var traffic = metrics.snapshot().serverTraffic().get("survival-1");
        assertEquals(4, traffic.frontendToBackendBytes());
        assertEquals(0, traffic.backendToFrontendBytes());

        frontend.finishAndReleaseAll();
        backend.finishAndReleaseAll();
    }

    @Test
    void capturesRelayPayloadPrefixesWhenEnabled() {
        var metrics = new ProxyMetrics();
        metrics.startPayloadCapture(
                "cap-1",
                "survival-1",
                ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND,
                4,
                2,
                Instant.now().plusSeconds(30));
        var backend = new EmbeddedChannel();
        var frontend = new EmbeddedChannel(new FrontendRelayHandler(backend, metrics, "survival-1"));

        frontend.writeInbound(Unpooled.wrappedBuffer(new byte[] {1, 2, 3, 4}));

        releaseOutbound(backend);
        var samples = metrics.payloadCaptureSamples("cap-1");
        assertEquals(1, samples.size());
        assertEquals(4, samples.getFirst().rawBytes());
        assertEquals(2, samples.getFirst().prefixBytes().length);
        assertEquals(1, samples.getFirst().prefixBytes()[0]);
        assertEquals(2, samples.getFirst().prefixBytes()[1]);

        frontend.finishAndReleaseAll();
        backend.finishAndReleaseAll();
    }

    @Test
    void capturesRelayPayloadPrefixesWithConnectionIdentity() {
        var metrics = new ProxyMetrics();
        metrics.startPayloadCapture(
                "cap-1",
                "survival-1",
                ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND,
                4,
                2,
                Instant.now().plusSeconds(30));
        var backend = new EmbeddedChannel();
        var identity = new RelaySessionIdentity("127.0.0.1:50000");
        identity.playerName("Steve");
        var frontend = new EmbeddedChannel(new FrontendRelayHandler(
                backend,
                metrics,
                "survival-1",
                new MinecraftCompressionAuditState(4096),
                CompressionRuntime.defaults(),
                4096,
                CustomPayloadAnomalyPolicy.defaults(),
                "Steve",
                identity));

        frontend.writeInbound(Unpooled.wrappedBuffer(new byte[] {1, 2, 3, 4}));

        releaseOutbound(backend);
        var sample = metrics.payloadCaptureSamples("cap-1").getFirst();
        assertEquals("Steve", sample.player());
        assertEquals("127.0.0.1:50000", sample.remoteAddress());

        frontend.finishAndReleaseAll();
        backend.finishAndReleaseAll();
    }

    @Test
    void attributesBackendTrafficToSelectedServer() {
        var metrics = new ProxyMetrics();
        var frontend = new EmbeddedChannel();
        var backend = new EmbeddedChannel(new BackendRelayHandler(frontend, metrics, "survival-1"));

        backend.writeInbound(Unpooled.wrappedBuffer(new byte[] {1, 2, 3}));

        releaseOutbound(frontend);
        var traffic = metrics.snapshot().serverTraffic().get("survival-1");
        assertEquals(0, traffic.frontendToBackendBytes());
        assertEquals(3, traffic.backendToFrontendBytes());

        backend.finishAndReleaseAll();
        frontend.finishAndReleaseAll();
    }

    @Test
    void recordsBackendLoginPluginRequestPayloadDiagnosticsWithoutChangingForwardedBytes() {
        var metrics = new ProxyMetrics();
        var frontend = new EmbeddedChannel();
        var identity = new RelaySessionIdentity("127.0.0.1:50000");
        identity.playerName("Steve");
        var backend = new EmbeddedChannel(new BackendRelayHandler(
                frontend,
                metrics,
                "survival-1",
                4096,
                new MinecraftCompressionAuditState(4096),
                CompressionRuntime.defaults(),
                identity));
        var frame = loginPluginRequestFrame(0x04, 7, "fml:handshake", 96);

        backend.writeInbound(frame.retainedDuplicate());

        var forwarded = (ByteBuf) frontend.readOutbound();
        try {
            assertEquals(frame.readableBytes(), forwarded.readableBytes());
            var snapshot = metrics.snapshot();
            var payload = snapshot.customPayloads().get(new ProxyMetrics.CustomPayloadKey(
                    "survival-1",
                    ProxyMetrics.CompressionDirection.BACKEND_TO_FRONTEND,
                    "FORGE_HANDSHAKE",
                    "fml:handshake"));
            assertEquals(1, payload.packets());
            assertEquals(96, payload.payloadBytes());
            assertTrue(snapshot.recentCustomPayloads().stream()
                    .anyMatch(sample -> sample.server().equals("survival-1")
                            && sample.direction() == ProxyMetrics.CompressionDirection.BACKEND_TO_FRONTEND
                            && sample.kind().equals("FORGE_HANDSHAKE")
                            && sample.channel().equals("fml:handshake")
                            && sample.protocolState().equals("LOGIN")
                            && sample.packetId() == 0x04
                            && sample.player().equals("Steve")
                            && sample.remoteAddress().equals("127.0.0.1:50000")));
        } finally {
            release(forwarded);
            frame.release();
        }

        backend.finishAndReleaseAll();
        frontend.finishAndReleaseAll();
    }

    @Test
    void capturesBackendPayloadPrefixesWithConnectionIdentity() {
        var metrics = new ProxyMetrics();
        metrics.startPayloadCapture(
                "cap-1",
                "survival-1",
                ProxyMetrics.CompressionDirection.BACKEND_TO_FRONTEND,
                4,
                2,
                Instant.now().plusSeconds(30));
        var frontend = new EmbeddedChannel();
        var identity = new RelaySessionIdentity("127.0.0.1:50000");
        identity.playerName("Steve");
        var backend = new EmbeddedChannel(new BackendRelayHandler(
                frontend,
                metrics,
                "survival-1",
                4096,
                new MinecraftCompressionAuditState(4096),
                CompressionRuntime.defaults(),
                identity));

        backend.writeInbound(Unpooled.wrappedBuffer(new byte[] {1, 2, 3}));

        releaseOutbound(frontend);
        var sample = metrics.payloadCaptureSamples("cap-1").getFirst();
        assertEquals("Steve", sample.player());
        assertEquals("127.0.0.1:50000", sample.remoteAddress());

        backend.finishAndReleaseAll();
        frontend.finishAndReleaseAll();
    }

    @Test
    void frontendRelayClosesServerConnectionOnlyOnce() {
        var metrics = new ProxyMetrics();
        metrics.serverConnectionOpened("survival-1");
        var backend = new EmbeddedChannel();
        var frontend = new EmbeddedChannel(new FrontendRelayHandler(backend, metrics, "survival-1"));

        frontend.pipeline().fireExceptionCaught(new RuntimeException("boom"));
        frontend.pipeline().fireChannelInactive();

        var connections = metrics.snapshot().serverConnections().get("survival-1");
        assertEquals(1, connections.routedConnections());
        assertEquals(0, connections.activeConnections());

        frontend.finishAndReleaseAll();
        backend.finishAndReleaseAll();
    }

    @Test
    void recordsUncompressedPacketTrafficByDirectionAndPacketId() {
        var metrics = new ProxyMetrics();
        var backendOutbound = new EmbeddedChannel();
        var frontendRelay = new EmbeddedChannel(new FrontendRelayHandler(backendOutbound, metrics, "survival-1"));
        var frontendFrame = packetFrame(0x15, 0);
        var frontendFirst = frontendFrame.readRetainedSlice(1);
        var frontendSecond = frontendFrame.readRetainedSlice(frontendFrame.readableBytes());

        frontendRelay.writeInbound(frontendFirst);
        frontendRelay.writeInbound(frontendSecond);
        releaseOutbound(backendOutbound);

        var frontendTraffic = metrics.snapshot().packetTraffic().get(new ProxyMetrics.PacketTrafficKey(
                "survival-1",
                ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND,
                "UNCOMPRESSED",
                0x15));
        assertEquals(1, frontendTraffic.packets());
        assertTrue(frontendTraffic.rawBytes() > 0);

        var frontendOutbound = new EmbeddedChannel();
        var backendRelay = new EmbeddedChannel(new BackendRelayHandler(frontendOutbound, metrics, "survival-1", 4096));
        var backendFrame = packetFrame(0x22, 0);
        backendRelay.writeInbound(backendFrame.retainedDuplicate());
        releaseOutbound(frontendOutbound);

        var backendTraffic = metrics.snapshot().packetTraffic().get(new ProxyMetrics.PacketTrafficKey(
                "survival-1",
                ProxyMetrics.CompressionDirection.BACKEND_TO_FRONTEND,
                "UNCOMPRESSED",
                0x22));
        assertEquals(1, backendTraffic.packets());
        assertEquals(backendFrame.readableBytes(), backendTraffic.rawBytes());

        frontendFrame.release();
        backendFrame.release();
        frontendRelay.finishAndReleaseAll();
        backendRelay.finishAndReleaseAll();
        backendOutbound.finishAndReleaseAll();
        frontendOutbound.finishAndReleaseAll();
    }

    @Test
    void frontendRelayTracksLoginStartPlayerSessionAcrossSplits() {
        var metrics = new ProxyMetrics();
        var backend = new EmbeddedChannel();
        var frontend = new EmbeddedChannel(new FrontendRelayHandler(backend, metrics, "survival-1"));
        var login = loginStartFrame("Steve");
        var first = login.readRetainedSlice(2);
        var second = login.readRetainedSlice(login.readableBytes());

        frontend.writeInbound(first);
        frontend.writeInbound(second);
        releaseOutbound(backend);

        var session = metrics.snapshot().playerSessions().get("Steve");
        assertEquals("survival-1", session.server());

        frontend.pipeline().fireChannelInactive();

        assertTrue(metrics.snapshot().playerSessions().isEmpty());
        login.release();
        frontend.finishAndReleaseAll();
        backend.finishAndReleaseAll();
    }

    @Test
    void recordsBackendCompressionNegotiationWithoutChangingForwardedBytes() {
        var metrics = new ProxyMetrics();
        var frontend = new EmbeddedChannel();
        var backend = new EmbeddedChannel(new BackendRelayHandler(frontend, metrics, "survival-1", 1024));
        var frame = packetFrame(0x03, 256);

        backend.writeInbound(frame.retainedDuplicate());

        var forwarded = (ByteBuf) frontend.readOutbound();
        try {
            assertEquals(frame.readableBytes(), forwarded.readableBytes());
        } finally {
            forwarded.release();
            frame.release();
        }
        var snapshot = metrics.snapshot();
        assertEquals(1, snapshot.compressionNegotiations());
        assertEquals(256, snapshot.serverCompressionThresholds().get("survival-1"));

        backend.finishAndReleaseAll();
        frontend.finishAndReleaseAll();
    }

    @Test
    void recordsCompressionNegotiationAcrossSplitBackendFrames() {
        var metrics = new ProxyMetrics();
        var frontend = new EmbeddedChannel();
        var backend = new EmbeddedChannel(new BackendRelayHandler(frontend, metrics, "survival-1", 1024));
        var frame = packetFrame(0x03, 512);
        var first = frame.readRetainedSlice(1);
        var second = frame.readRetainedSlice(frame.readableBytes());

        backend.writeInbound(first);
        backend.writeInbound(second);

        releaseOutbound(frontend);
        var snapshot = metrics.snapshot();
        assertEquals(1, snapshot.compressionNegotiations());
        assertEquals(512, snapshot.serverCompressionThresholds().get("survival-1"));

        frame.release();
        backend.finishAndReleaseAll();
        frontend.finishAndReleaseAll();
    }

    @Test
    void doesNotTreatPlayPacketThreeAsCompressionAfterLoginSuccess() {
        var metrics = new ProxyMetrics();
        var frontend = new EmbeddedChannel();
        var backend = new EmbeddedChannel(new BackendRelayHandler(frontend, metrics, "survival-1", 1024));
        var loginSuccess = packetFrame(0x02, 0);
        var playPacketThree = packetFrame(0x03, 0);

        backend.writeInbound(loginSuccess.retainedDuplicate());
        backend.writeInbound(playPacketThree.retainedDuplicate());

        releaseOutbound(frontend);
        var snapshot = metrics.snapshot();
        assertEquals(0, snapshot.compressionNegotiations());
        assertEquals(false, snapshot.serverCompressionThresholds().containsKey("survival-1"));

        loginSuccess.release();
        playPacketThree.release();
        backend.finishAndReleaseAll();
        frontend.finishAndReleaseAll();
    }

    @Test
    void doesNotDetectCompressionNegotiationForLegacyProtocol() {
        var metrics = new ProxyMetrics();
        var frontend = new EmbeddedChannel();
        var backend = new EmbeddedChannel(new BackendRelayHandler(
                frontend,
                metrics,
                "survival-1",
                1024,
                new MinecraftCompressionAuditState(1024),
                CompressionRuntime.defaults(),
                new RelaySessionIdentity(""),
                MinecraftForwardingRuntime.none(),
                false,
                25,
                5,
                null));
        var playPacketThree = packetFrame(0x03, 128);

        backend.writeInbound(playPacketThree.retainedDuplicate());
        releaseOutbound(frontend);

        var snapshot = metrics.snapshot();
        assertEquals(0, snapshot.compressionNegotiations());
        assertEquals(false, snapshot.serverCompressionThresholds().containsKey("survival-1"));

        playPacketThree.release();
        backend.finishAndReleaseAll();
        frontend.finishAndReleaseAll();
    }

    @Test
    void malformedCompressionNegotiationClosesRelay() {
        var metrics = new ProxyMetrics();
        var frontend = new EmbeddedChannel();
        var backend = new EmbeddedChannel(new BackendRelayHandler(frontend, metrics, "survival-1", 2));
        var frame = Unpooled.buffer();
        MinecraftVarInts.write(frame, 3);
        frame.writeZero(3);

        backend.writeInbound(frame.retainedDuplicate());

        assertNull(frontend.readOutbound());
        var snapshot = metrics.snapshot();
        assertEquals(0, snapshot.compressionNegotiations());
        assertEquals(1, snapshot.packetAnomalies().get("compression-negotiation-malformed"));

        frame.release();
        backend.finishAndReleaseAll();
        frontend.finishAndReleaseAll();
    }

    @Test
    void samplesCompressedBackendFramesAfterNegotiation() {
        var metrics = new ProxyMetrics();
        var frontend = new EmbeddedChannel();
        var backend = new EmbeddedChannel(new BackendRelayHandler(frontend, metrics, "survival-1", 4096));
        var negotiation = packetFrame(0x03, 32);
        var packet = Unpooled.wrappedBuffer("compress-me-".repeat(32).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try (var codec = new MinecraftCompressionCodec()) {
            var compressed = codec.encodeFrame(UnpooledByteBufAllocatorHolder.ALLOCATOR, packet, 32);
            try {
                backend.writeInbound(negotiation.retainedDuplicate());
                releaseOutbound(frontend);

                backend.writeInbound(compressed.retainedDuplicate());
                releaseOutbound(frontend);

                var audit = metrics.snapshot().serverCompression().get("survival-1");
                assertEquals(1, audit.samples());
                assertEquals(packet.readableBytes(), audit.rawBytes());
                assertEquals(compressed.readableBytes(), audit.compressedBytes());
                assertTrue(audit.savedBytes() > 0);
                assertEquals(1, metrics.snapshot().compressionDecisions().values().stream().mapToLong(Long::longValue).sum());
            } finally {
                compressed.release();
                packet.release();
                negotiation.release();
            }
        }

        backend.finishAndReleaseAll();
        frontend.finishAndReleaseAll();
    }

    @Test
    void samplesCompressedFrontendFramesAfterBackendNegotiation() {
        var metrics = new ProxyMetrics();
        var compressionAudit = new MinecraftCompressionAuditState(4096);
        var frontendOutbound = new EmbeddedChannel();
        var backendOutbound = new EmbeddedChannel();
        var backendRelay = new EmbeddedChannel(new BackendRelayHandler(
                frontendOutbound,
                metrics,
                "survival-1",
                4096,
                compressionAudit));
        var frontendRelay = new EmbeddedChannel(new FrontendRelayHandler(
                backendOutbound,
                metrics,
                "survival-1",
                compressionAudit));
        var negotiation = packetFrame(0x03, 32);
        var packet = Unpooled.wrappedBuffer("client-compress-me-".repeat(16).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try (var codec = new MinecraftCompressionCodec()) {
            var compressed = codec.encodeFrame(UnpooledByteBufAllocatorHolder.ALLOCATOR, packet, 32);
            try {
                backendRelay.writeInbound(negotiation.retainedDuplicate());
                releaseOutbound(frontendOutbound);

                frontendRelay.writeInbound(compressed.retainedDuplicate());
                var forwarded = (ByteBuf) backendOutbound.readOutbound();
                try {
                    assertEquals(compressed.readableBytes(), forwarded.readableBytes());
                } finally {
                    forwarded.release();
                }

                var audit = metrics.snapshot().serverCompression().get("survival-1");
                assertEquals(1, audit.samples());
                assertEquals(packet.readableBytes(), audit.rawBytes());
                assertEquals(compressed.readableBytes(), audit.compressedBytes());
                assertTrue(audit.savedBytes() > 0);
                assertEquals(1, metrics.snapshot().compressionDecisions().values().stream().mapToLong(Long::longValue).sum());
            } finally {
                compressed.release();
                packet.release();
                negotiation.release();
            }
        }

        backendRelay.finishAndReleaseAll();
        frontendRelay.finishAndReleaseAll();
        frontendOutbound.finishAndReleaseAll();
        backendOutbound.finishAndReleaseAll();
    }

    @Test
    void rewritesCompressedFrontendFrameWhenEnabledAndPolicyChangesThreshold() {
        var metrics = new ProxyMetrics();
        var compressionAudit = new MinecraftCompressionAuditState(4096);
        var frontendOutbound = new EmbeddedChannel();
        var backendOutbound = new EmbeddedChannel();
        var targetThreshold = 4096;
        var backendRelay = new EmbeddedChannel(new BackendRelayHandler(
                frontendOutbound,
                metrics,
                "survival-1",
                4096,
                compressionAudit));
        var frontendRelay = new EmbeddedChannel(new FrontendRelayHandler(
                backendOutbound,
                metrics,
                "survival-1",
                compressionAudit,
                new CompressionRuntime(new FixedCompressionStrategy(), targetThreshold, targetThreshold, 0.75d),
                4096,
                CustomPayloadAnomalyPolicy.defaults(),
                null,
                new RelaySessionIdentity(""),
                true));
        var negotiation = packetFrame(0x03, 32);
        var packet = Unpooled.wrappedBuffer("client-compress-me-".repeat(16).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try (var codec = new MinecraftCompressionCodec()) {
            var compressed = codec.encodeFrame(UnpooledByteBufAllocatorHolder.ALLOCATOR, packet, 32);
            ByteBuf forwarded = null;
            ByteBuf decoded = null;
            try {
                backendRelay.writeInbound(negotiation.retainedDuplicate());
                releaseOutbound(frontendOutbound);

                frontendRelay.writeInbound(compressed.retainedDuplicate());
                forwarded = backendOutbound.readOutbound();

                assertTrue(forwarded.readableBytes() > compressed.readableBytes());
                decoded = codec.decodeFrame(UnpooledByteBufAllocatorHolder.ALLOCATOR, forwarded.slice(), targetThreshold, 4096);
                assertEquals(packet.readableBytes(), decoded.readableBytes());

                var rewriteCount = metrics.snapshot().compressionRewrites().get(new ProxyMetrics.CompressionRewriteKey(
                        "survival-1",
                        ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND,
                        "rewritten"));
                assertEquals(1, rewriteCount.count());
            } finally {
                release(forwarded);
                release(decoded);
                compressed.release();
                packet.release();
                negotiation.release();
            }
        }

        backendRelay.finishAndReleaseAll();
        frontendRelay.finishAndReleaseAll();
        frontendOutbound.finishAndReleaseAll();
        backendOutbound.finishAndReleaseAll();
    }

    @Test
    void rewritesCoalescedCompressedFrontendFramesWhenEnabled() {
        var metrics = new ProxyMetrics();
        var compressionAudit = new MinecraftCompressionAuditState(8192);
        var frontendOutbound = new EmbeddedChannel();
        var backendOutbound = new EmbeddedChannel();
        var targetThreshold = 8192;
        var backendRelay = new EmbeddedChannel(new BackendRelayHandler(
                frontendOutbound,
                metrics,
                "survival-1",
                8192,
                compressionAudit));
        var frontendRelay = new EmbeddedChannel(new FrontendRelayHandler(
                backendOutbound,
                metrics,
                "survival-1",
                compressionAudit,
                new CompressionRuntime(new FixedCompressionStrategy(), targetThreshold, targetThreshold, 0.75d),
                8192,
                CustomPayloadAnomalyPolicy.defaults(),
                null,
                new RelaySessionIdentity(""),
                true));
        var negotiation = packetFrame(0x03, 32);
        var firstPacket = Unpooled.wrappedBuffer("first-client-compress-me-".repeat(12).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var secondPacket = Unpooled.wrappedBuffer("second-client-compress-me-".repeat(12).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try (var codec = new MinecraftCompressionCodec()) {
            var firstCompressed = codec.encodeFrame(UnpooledByteBufAllocatorHolder.ALLOCATOR, firstPacket, 32);
            var secondCompressed = codec.encodeFrame(UnpooledByteBufAllocatorHolder.ALLOCATOR, secondPacket, 32);
            var coalesced = Unpooled.buffer(firstCompressed.readableBytes() + secondCompressed.readableBytes());
            ByteBuf forwarded = null;
            ByteBuf firstDecoded = null;
            ByteBuf secondDecoded = null;
            try {
                coalesced.writeBytes(firstCompressed, firstCompressed.readerIndex(), firstCompressed.readableBytes());
                coalesced.writeBytes(secondCompressed, secondCompressed.readerIndex(), secondCompressed.readableBytes());

                backendRelay.writeInbound(negotiation.retainedDuplicate());
                releaseOutbound(frontendOutbound);

                frontendRelay.writeInbound(coalesced.retainedDuplicate());
                forwarded = backendOutbound.readOutbound();
                assertTrue(forwarded.readableBytes() > coalesced.readableBytes());

                var view = forwarded.slice();
                firstDecoded = codec.decodeFrame(UnpooledByteBufAllocatorHolder.ALLOCATOR, view, targetThreshold, 8192);
                secondDecoded = codec.decodeFrame(UnpooledByteBufAllocatorHolder.ALLOCATOR, view, targetThreshold, 8192);
                assertEquals(0, view.readableBytes());
                assertArrayEquals(toBytes(firstPacket), toBytes(firstDecoded));
                assertArrayEquals(toBytes(secondPacket), toBytes(secondDecoded));

                var rewriteCount = metrics.snapshot().compressionRewrites().get(new ProxyMetrics.CompressionRewriteKey(
                        "survival-1",
                        ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND,
                        "rewritten"));
                assertEquals(1, rewriteCount.count());
                assertEquals(2, metrics.snapshot().compressionDecisions().values().stream().mapToLong(Long::longValue).sum());
            } finally {
                release(forwarded);
                release(firstDecoded);
                release(secondDecoded);
                coalesced.release();
                firstCompressed.release();
                secondCompressed.release();
                firstPacket.release();
                secondPacket.release();
                negotiation.release();
            }
        }

        backendRelay.finishAndReleaseAll();
        frontendRelay.finishAndReleaseAll();
        frontendOutbound.finishAndReleaseAll();
        backendOutbound.finishAndReleaseAll();
    }

    @Test
    void buffersPartialCompressedFrontendFrameUntilRewriteCanComplete() {
        var metrics = new ProxyMetrics();
        var compressionAudit = new MinecraftCompressionAuditState(8192);
        var frontendOutbound = new EmbeddedChannel();
        var backendOutbound = new EmbeddedChannel();
        var targetThreshold = 8192;
        var backendRelay = new EmbeddedChannel(new BackendRelayHandler(
                frontendOutbound,
                metrics,
                "survival-1",
                8192,
                compressionAudit));
        var frontendRelay = new EmbeddedChannel(new FrontendRelayHandler(
                backendOutbound,
                metrics,
                "survival-1",
                compressionAudit,
                new CompressionRuntime(new FixedCompressionStrategy(), targetThreshold, targetThreshold, 0.75d),
                8192,
                CustomPayloadAnomalyPolicy.defaults(),
                null,
                new RelaySessionIdentity(""),
                true));
        var negotiation = packetFrame(0x03, 32);
        var packet = Unpooled.wrappedBuffer("split-client-compress-me-".repeat(16).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try (var codec = new MinecraftCompressionCodec()) {
            var compressed = codec.encodeFrame(UnpooledByteBufAllocatorHolder.ALLOCATOR, packet, 32);
            ByteBuf forwarded = null;
            ByteBuf decoded = null;
            try {
                backendRelay.writeInbound(negotiation.retainedDuplicate());
                releaseOutbound(frontendOutbound);

                var splitAt = Math.max(1, compressed.readableBytes() / 2);
                frontendRelay.writeInbound(compressed.retainedSlice(compressed.readerIndex(), splitAt));
                assertNull(backendOutbound.readOutbound());

                frontendRelay.writeInbound(compressed.retainedSlice(compressed.readerIndex() + splitAt, compressed.readableBytes() - splitAt));
                forwarded = backendOutbound.readOutbound();
                assertTrue(forwarded.readableBytes() > compressed.readableBytes());

                decoded = codec.decodeFrame(UnpooledByteBufAllocatorHolder.ALLOCATOR, forwarded.slice(), targetThreshold, 8192);
                assertArrayEquals(toBytes(packet), toBytes(decoded));

                var rewriteCount = metrics.snapshot().compressionRewrites().get(new ProxyMetrics.CompressionRewriteKey(
                        "survival-1",
                        ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND,
                        "rewritten"));
                assertEquals(1, rewriteCount.count());
            } finally {
                release(forwarded);
                release(decoded);
                compressed.release();
                packet.release();
                negotiation.release();
            }
        }

        backendRelay.finishAndReleaseAll();
        frontendRelay.finishAndReleaseAll();
        frontendOutbound.finishAndReleaseAll();
        backendOutbound.finishAndReleaseAll();
    }

    @Test
    void skipsCompressionRewriteWhenEventLoopDelayExceedsGuard() {
        var metrics = new ProxyMetrics();
        metrics.eventLoopDelayNanos(50_000_000L);
        var compressionAudit = new MinecraftCompressionAuditState(4096);
        var frontendOutbound = new EmbeddedChannel();
        var backendOutbound = new EmbeddedChannel();
        var targetThreshold = 4096;
        var backendRelay = new EmbeddedChannel(new BackendRelayHandler(
                frontendOutbound,
                metrics,
                "survival-1",
                4096,
                compressionAudit));
        var frontendRelay = new EmbeddedChannel(new FrontendRelayHandler(
                backendOutbound,
                metrics,
                "survival-1",
                compressionAudit,
                new CompressionRuntime(new FixedCompressionStrategy(), targetThreshold, targetThreshold, 0.75d),
                4096,
                CustomPayloadAnomalyPolicy.defaults(),
                null,
                new RelaySessionIdentity(""),
                true,
                10));
        var negotiation = packetFrame(0x03, 32);
        var packet = Unpooled.wrappedBuffer("guard-client-compress-me-".repeat(12).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try (var codec = new MinecraftCompressionCodec()) {
            var compressed = codec.encodeFrame(UnpooledByteBufAllocatorHolder.ALLOCATOR, packet, 32);
            ByteBuf forwarded = null;
            try {
                backendRelay.writeInbound(negotiation.retainedDuplicate());
                releaseOutbound(frontendOutbound);

                frontendRelay.writeInbound(compressed.retainedDuplicate());
                forwarded = backendOutbound.readOutbound();
                assertEquals(compressed.readableBytes(), forwarded.readableBytes());

                var guardCount = metrics.snapshot().compressionRewrites().get(new ProxyMetrics.CompressionRewriteKey(
                        "survival-1",
                        ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND,
                        "event_loop_guard"));
                assertEquals(1, guardCount.count());
            } finally {
                release(forwarded);
                compressed.release();
                packet.release();
                negotiation.release();
            }
        }

        backendRelay.finishAndReleaseAll();
        frontendRelay.finishAndReleaseAll();
        frontendOutbound.finishAndReleaseAll();
        backendOutbound.finishAndReleaseAll();
    }

    @Test
    void warnsOnLargeKnownCustomPayloadsWithoutChangingForwardedBytes() {
        var metrics = new ProxyMetrics();
        var backend = new EmbeddedChannel();
        var frontend = new EmbeddedChannel(new FrontendRelayHandler(backend, metrics, "survival-1"));
        var frame = customPayloadFrame(0x01, "minecraft:brand", 2 * 1024 * 1024);

        frontend.writeInbound(frame.retainedDuplicate());

        var forwarded = (ByteBuf) backend.readOutbound();
        try {
            assertEquals(frame.readableBytes(), forwarded.readableBytes());
        } finally {
            forwarded.release();
            frame.release();
        }
        var snapshot = metrics.snapshot();
        var payload = snapshot.customPayloads().get(new ProxyMetrics.CustomPayloadKey(
                "survival-1",
                ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND,
                "BRAND",
                "minecraft:brand"));
        assertEquals(1, payload.packets());
        assertEquals(2 * 1024 * 1024, payload.payloadBytes());
        assertEquals(0, payload.compressedBytes());
        assertEquals(2 * 1024 * 1024, payload.maxPayloadBytes());
        assertEquals(0, payload.maxCompressedBytes());
        assertTrue(payload.firstSeen() != null);
        assertTrue(payload.lastSeen() != null);
        assertEquals(1, snapshot.packetAnomalies().get("custom-payload-large"));
        assertEquals(0, snapshot.packetAnomalies().getOrDefault("custom-payload-unknown-large", 0L));
        assertTrue(snapshot.recentPacketAnomalies().stream()
                .anyMatch(sample -> sample.rule().equals("custom-payload-large")
                        && sample.server().equals("survival-1")
                        && sample.direction().equals("frontend_to_backend")
                        && sample.protocolState().equals("CONFIGURATION")
                        && sample.detail().contains("minecraft:brand")));

        frontend.finishAndReleaseAll();
        backend.finishAndReleaseAll();
    }

    @Test
    void throttlesLargeUnknownCustomPayloadsBeforeForwarding() {
        var metrics = new ProxyMetrics();
        var backend = new EmbeddedChannel();
        var frontend = new EmbeddedChannel(new FrontendRelayHandler(backend, metrics, "survival-1"));
        var frame = customPayloadFrame(0x01, "unknown:blob", 2 * 1024 * 1024);

        frontend.writeInbound(frame.retainedDuplicate());

        assertNull(backend.readOutbound());
        frame.release();
        var snapshot = metrics.snapshot();
        assertEquals(1, snapshot.packetAnomalies().get("custom-payload-large"));
        assertEquals(1, snapshot.packetAnomalies().get("custom-payload-unknown-large"));
        assertTrue(snapshot.recentPacketAnomalies().stream()
                .anyMatch(sample -> sample.rule().equals("custom-payload-unknown-large")
                        && sample.server().equals("survival-1")
                        && sample.direction().equals("frontend_to_backend")
                        && sample.protocolState().equals("CONFIGURATION")
                        && sample.detail().contains("action=THROTTLE")));

        frontend.finishAndReleaseAll();
        backend.finishAndReleaseAll();
    }

    @Test
    void customPayloadInspectionUsesConfiguredThrottleThresholds() {
        var metrics = new ProxyMetrics();
        var backend = new EmbeddedChannel();
        var frontend = new EmbeddedChannel(new FrontendRelayHandler(
                backend,
                metrics,
                "survival-1",
                new MinecraftCompressionAuditState(4096),
                CompressionRuntime.defaults(),
                4096,
                new CustomPayloadAnomalyPolicy(4096, 1024, 4096)));
        var frame = customPayloadFrame(0x01, "unknown:blob", 2048);

        frontend.writeInbound(frame.retainedDuplicate());

        assertNull(backend.readOutbound());
        frame.release();
        var anomalies = metrics.snapshot().packetAnomalies();
        assertEquals(0, anomalies.getOrDefault("custom-payload-large", 0L));
        assertEquals(1, anomalies.get("custom-payload-unknown-large"));

        frontend.finishAndReleaseAll();
        backend.finishAndReleaseAll();
    }

    @Test
    void throttlesCompressedLargeUnknownCustomPayloadsBeforeForwarding() {
        var metrics = new ProxyMetrics();
        var compressionAudit = new MinecraftCompressionAuditState(4096);
        compressionAudit.negotiate(32);
        var backend = new EmbeddedChannel();
        var frontend = new EmbeddedChannel(new FrontendRelayHandler(
                backend,
                metrics,
                "survival-1",
                compressionAudit,
                CompressionRuntime.defaults(),
                4096,
                new CustomPayloadAnomalyPolicy(4096, 1024, 4096)));
        var packet = customPayloadPacket(0x01, "unknown:blob", 2048);
        try (var codec = new MinecraftCompressionCodec()) {
            var compressed = codec.encodeFrame(UnpooledByteBufAllocatorHolder.ALLOCATOR, packet, 32);
            try {
                frontend.writeInbound(compressed.retainedDuplicate());

                assertNull(backend.readOutbound());
                var snapshot = metrics.snapshot();
                assertEquals(1, snapshot.packetAnomalies().get("custom-payload-unknown-large"));
                assertTrue(snapshot.recentPacketAnomalies().stream()
                        .anyMatch(sample -> sample.rule().equals("custom-payload-unknown-large")
                                && sample.server().equals("survival-1")
                                && sample.direction().equals("frontend_to_backend")
                                && sample.protocolState().equals("CONFIGURATION")
                                && sample.compressedSize() == compressed.readableBytes()
                                && sample.detail().contains("action=THROTTLE")));
            } finally {
                compressed.release();
                packet.release();
            }
        }

        frontend.finishAndReleaseAll();
        backend.finishAndReleaseAll();
    }

    @Test
    void throttlesCustomPayloadFloodsBeforeForwardingTriggeringFrame() {
        var metrics = new ProxyMetrics();
        var backend = new EmbeddedChannel();
        var frontend = new EmbeddedChannel(new FrontendRelayHandler(
                backend,
                metrics,
                "survival-1",
                new MinecraftCompressionAuditState(4096),
                CompressionRuntime.defaults(),
                4096,
                new CustomPayloadAnomalyPolicy(4096, 4096, 4096, 2, Duration.ofSeconds(10))));
        var first = customPayloadFrame(0x01, "minecraft:brand", 16);
        var second = customPayloadFrame(0x01, "minecraft:brand", 16);
        var third = customPayloadFrame(0x01, "minecraft:brand", 16);

        frontend.writeInbound(first.retainedDuplicate());
        frontend.writeInbound(second.retainedDuplicate());
        releaseOutbound(backend);
        frontend.writeInbound(third.retainedDuplicate());

        assertNull(backend.readOutbound());
        first.release();
        second.release();
        third.release();
        var snapshot = metrics.snapshot();
        assertEquals(1, snapshot.packetAnomalies().get("custom-payload-flood"));
        assertTrue(snapshot.recentPacketAnomalies().stream()
                .anyMatch(sample -> sample.rule().equals("custom-payload-flood")
                        && sample.detail().contains("action=THROTTLE")));

        frontend.finishAndReleaseAll();
        backend.finishAndReleaseAll();
    }

    @Test
    void inspectsUncompressedFrontendCustomPayloadsAcrossSplits() {
        var metrics = new ProxyMetrics();
        var backend = new EmbeddedChannel();
        var frontend = new EmbeddedChannel(new FrontendRelayHandler(backend, metrics, "survival-1"));
        var frame = customPayloadFrame(0x01, "fml:handshake", 2 * 1024 * 1024);
        var first = frame.readRetainedSlice(2);
        var second = frame.readRetainedSlice(frame.readableBytes());

        frontend.writeInbound(first);
        frontend.writeInbound(second);

        releaseOutbound(backend);
        assertEquals(1, metrics.snapshot().packetAnomalies().get("modded-handshake-large"));

        frame.release();
        frontend.finishAndReleaseAll();
        backend.finishAndReleaseAll();
    }

    @Test
    void samplesCompressedFrontendFramesAcrossSplits() {
        var metrics = new ProxyMetrics();
        var compressionAudit = new MinecraftCompressionAuditState(4096);
        compressionAudit.negotiate(32);
        var backendOutbound = new EmbeddedChannel();
        var frontendRelay = new EmbeddedChannel(new FrontendRelayHandler(
                backendOutbound,
                metrics,
                "survival-1",
                compressionAudit));
        var packet = Unpooled.wrappedBuffer("client-split-compress-me-".repeat(16).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try (var codec = new MinecraftCompressionCodec()) {
            var compressed = codec.encodeFrame(UnpooledByteBufAllocatorHolder.ALLOCATOR, packet, 32);
            var first = compressed.readRetainedSlice(2);
            var second = compressed.readRetainedSlice(compressed.readableBytes());
            try {
                frontendRelay.writeInbound(first);
                frontendRelay.writeInbound(second);
                releaseOutbound(backendOutbound);

                var audit = metrics.snapshot().serverCompression().get("survival-1");
                assertEquals(1, audit.samples());
                assertEquals(packet.readableBytes(), audit.rawBytes());
            } finally {
                compressed.release();
                packet.release();
            }
        }

        frontendRelay.finishAndReleaseAll();
        backendOutbound.finishAndReleaseAll();
    }

    @Test
    void samplesCompressedBackendFramesAcrossSplits() {
        var metrics = new ProxyMetrics();
        var frontend = new EmbeddedChannel();
        var backend = new EmbeddedChannel(new BackendRelayHandler(frontend, metrics, "survival-1", 4096));
        var negotiation = packetFrame(0x03, 32);
        var packet = Unpooled.wrappedBuffer("split-compress-me-".repeat(16).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try (var codec = new MinecraftCompressionCodec()) {
            var compressed = codec.encodeFrame(UnpooledByteBufAllocatorHolder.ALLOCATOR, packet, 32);
            var first = compressed.readRetainedSlice(2);
            var second = compressed.readRetainedSlice(compressed.readableBytes());
            try {
                backend.writeInbound(negotiation.retainedDuplicate());
                releaseOutbound(frontend);
                backend.writeInbound(first);
                backend.writeInbound(second);
                releaseOutbound(frontend);

                var audit = metrics.snapshot().serverCompression().get("survival-1");
                assertEquals(1, audit.samples());
                assertEquals(packet.readableBytes(), audit.rawBytes());
            } finally {
                compressed.release();
                packet.release();
                negotiation.release();
            }
        }

        backend.finishAndReleaseAll();
        frontend.finishAndReleaseAll();
    }

    @Test
    void malformedCompressionAuditClosesRelay() {
        var metrics = new ProxyMetrics();
        var frontend = new EmbeddedChannel();
        var backend = new EmbeddedChannel(new BackendRelayHandler(frontend, metrics, "survival-1", 4096));
        var negotiation = packetFrame(0x03, 64);
        var malformed = Unpooled.buffer();
        MinecraftVarInts.write(malformed, 2);
        MinecraftVarInts.write(malformed, 8);
        malformed.writeByte(0);

        backend.writeInbound(negotiation.retainedDuplicate());
        releaseOutbound(frontend);
        backend.writeInbound(malformed.retainedDuplicate());

        assertNull(frontend.readOutbound());
        malformed.release();
        negotiation.release();
        var snapshot = metrics.snapshot();
        assertEquals(1, snapshot.packetAnomalies().get("compression-audit-malformed"));
        assertEquals(0, snapshot.serverCompression().getOrDefault(
                "survival-1",
                new ProxyMetrics.CompressionAudit(0, 0, 0, 0, 0)).samples());

        backend.finishAndReleaseAll();
        frontend.finishAndReleaseAll();
    }

    @Test
    void compressionBombDeclaredSizeClosesRelay() {
        var metrics = new ProxyMetrics();
        var frontend = new EmbeddedChannel();
        var backend = new EmbeddedChannel(new BackendRelayHandler(frontend, metrics, "survival-1", 4096));
        var negotiation = packetFrame(0x03, 64);
        var bomb = Unpooled.buffer();
        var body = Unpooled.buffer();
        MinecraftVarInts.write(body, 8192);
        body.writeByte(0);
        MinecraftVarInts.write(bomb, body.readableBytes());
        bomb.writeBytes(body);
        body.release();

        backend.writeInbound(negotiation.retainedDuplicate());
        releaseOutbound(frontend);
        backend.writeInbound(bomb.retainedDuplicate());

        assertNull(frontend.readOutbound());
        bomb.release();
        negotiation.release();
        var snapshot = metrics.snapshot();
        assertEquals(1, snapshot.packetAnomalies().get("compression-audit-malformed"));
        assertTrue(snapshot.recentPacketAnomalies().stream()
                .anyMatch(sample -> sample.detail().contains("maximum uncompressed size")));

        backend.finishAndReleaseAll();
        frontend.finishAndReleaseAll();
    }

    @Test
    void frontendCompressionBombDeclaredSizeClosesRelay() {
        var metrics = new ProxyMetrics();
        var compressionAudit = new MinecraftCompressionAuditState(4096);
        compressionAudit.negotiate(64);
        var backend = new EmbeddedChannel();
        var frontend = new EmbeddedChannel(new FrontendRelayHandler(
                backend,
                metrics,
                "survival-1",
                compressionAudit));
        var bomb = Unpooled.buffer();
        var body = Unpooled.buffer();
        MinecraftVarInts.write(body, 8192);
        body.writeByte(0);
        MinecraftVarInts.write(bomb, body.readableBytes());
        bomb.writeBytes(body);
        body.release();

        frontend.writeInbound(bomb.retainedDuplicate());

        assertNull(backend.readOutbound());
        bomb.release();
        var snapshot = metrics.snapshot();
        assertEquals(1, snapshot.packetAnomalies().get("compression-audit-malformed"));
        assertTrue(snapshot.recentPacketAnomalies().stream()
                .anyMatch(sample -> sample.direction().equals("frontend_to_backend")
                        && sample.detail().contains("maximum uncompressed size")));

        frontend.finishAndReleaseAll();
        backend.finishAndReleaseAll();
    }

    private static void releaseOutbound(EmbeddedChannel channel) {
        ByteBuf outbound;
        while ((outbound = channel.readOutbound()) != null) {
            outbound.release();
        }
    }

    private static void release(ByteBuf buffer) {
        if (buffer != null) {
            buffer.release();
        }
    }

    private static byte[] toBytes(ByteBuf buffer) {
        var bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }

    private static ByteBuf packetFrame(int packetId, int threshold) {
        var payload = Unpooled.buffer();
        MinecraftVarInts.write(payload, packetId);
        MinecraftVarInts.write(payload, threshold);

        var frame = Unpooled.buffer();
        MinecraftVarInts.write(frame, payload.readableBytes());
        frame.writeBytes(payload);
        payload.release();
        return frame;
    }

    private static ByteBuf loginStartFrame(String username) {
        var payload = Unpooled.buffer();
        MinecraftVarInts.write(payload, 0);
        var bytes = username.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MinecraftVarInts.write(payload, bytes.length);
        payload.writeBytes(bytes);

        var frame = Unpooled.buffer();
        MinecraftVarInts.write(frame, payload.readableBytes());
        frame.writeBytes(payload);
        payload.release();
        return frame;
    }

    private static ByteBuf customPayloadFrame(int packetId, String channel, int payloadBytes) {
        var payload = customPayloadPacket(packetId, channel, payloadBytes);
        var frame = Unpooled.buffer();
        MinecraftVarInts.write(frame, payload.readableBytes());
        frame.writeBytes(payload);
        payload.release();
        return frame;
    }

    private static ByteBuf customPayloadPacket(int packetId, String channel, int payloadBytes) {
        var payload = Unpooled.buffer();
        MinecraftVarInts.write(payload, packetId);
        var channelBytes = channel.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MinecraftVarInts.write(payload, channelBytes.length);
        payload.writeBytes(channelBytes);
        payload.writeZero(payloadBytes);
        return payload;
    }

    private static ByteBuf loginPluginRequestFrame(int packetId, int messageId, String channel, int payloadBytes) {
        var payload = Unpooled.buffer();
        MinecraftVarInts.write(payload, packetId);
        MinecraftVarInts.write(payload, messageId);
        var channelBytes = channel.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MinecraftVarInts.write(payload, channelBytes.length);
        payload.writeBytes(channelBytes);
        payload.writeZero(payloadBytes);

        var frame = Unpooled.buffer();
        MinecraftVarInts.write(frame, payload.readableBytes());
        frame.writeBytes(payload);
        payload.release();
        return frame;
    }

    private static final class UnpooledByteBufAllocatorHolder {
        private static final io.netty.buffer.ByteBufAllocator ALLOCATOR = io.netty.buffer.UnpooledByteBufAllocator.DEFAULT;
    }
}

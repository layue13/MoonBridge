package dev.strataproxy.observability;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProxyMetricsTest {
    @Test
    void recordsRuntimeMetrics() {
        var metrics = new ProxyMetrics();

        metrics.eventLoopDelayNanos(100);
        metrics.eventLoopDelayNanos(50);
        metrics.pooledDirectMemoryBytes(4096);
        metrics.networkTransport("nio", false);
        metrics.nativeRuntime(true, "Linux", "amd64", "/proc/cpuinfo", "jdk-aes-intrinsics", "jdk-deflater-native-zlib", true, false, Map.of("aes", true, "avx2", true));
        metrics.rejectedConnection("global_limit");
        metrics.rejectedConnection("per_address_limit");
        metrics.rejectedConnection("per_address_limit");
        metrics.handshakeTimeout();
        metrics.packetAnomaly("malformed-varint");
        metrics.packetAnomaly("malformed-varint", "127.0.0.1:50000", "survival-1", "frontend_to_backend", "HANDSHAKE", 0, 64, -1, "bad frame");
        metrics.packetTraffic("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, "UNCOMPRESSED", 1, 64, 0);
        metrics.packetTraffic("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, "UNCOMPRESSED", 1, 32, 0);
        metrics.customPayload("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, "FORGE_HANDSHAKE", "fml:handshake", 128, 0);
        metrics.customPayload("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, "FORGE_HANDSHAKE", "fml:handshake", 256, 0);
        metrics.customPayload("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, "UNKNOWN", "attacker:random", 512, 0);
        metrics.forgeHandshake(
                "survival-1",
                "Alex",
                "127.0.0.1:50000",
                "REGISTRY_DATA",
                "WAITING_SERVER_COMPLETE",
                "REGISTRY_DATA_SENT",
                false,
                true,
                128,
                130,
                9,
                65536);
        metrics.frontendToBackendBytes("survival-1", 128);
        metrics.backendToFrontendBytes("survival-1", 256);
        metrics.compressionSample("survival-1", 1000, 400, 2_000_000);
        metrics.compressionSample("survival-1", 500, 250, 1_000_000);
        metrics.compressionSample("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, 300, 100, 0);
        metrics.compressionNegotiated("survival-1", 256);
        metrics.compressionDecision("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, "threshold", 1024);
        metrics.compressionDecision("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, "threshold", 1024);
        metrics.relayBackpressure("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, 128);
        metrics.relayBackpressure("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, 256);
        metrics.backendReplacement("attempted");
        metrics.backendReplacement("success");
        metrics.backendReplacement("success");
        metrics.serverConnectionOpened("survival-1");
        metrics.serverConnectionOpened("survival-1");
        metrics.serverConnectionClosed("survival-1");

        var snapshot = metrics.snapshot();
        assertEquals(50, snapshot.eventLoopDelayNanos());
        assertEquals(100, snapshot.maxEventLoopDelayNanos());
        assertEquals(4096, snapshot.pooledDirectMemoryBytes());
        assertEquals("nio", snapshot.networkTransport().name());
        assertEquals(false, snapshot.networkTransport().nativeTransport());
        assertTrue(snapshot.nativeRuntime().enabled());
        assertEquals("Linux", snapshot.nativeRuntime().os());
        assertEquals("jdk-aes-intrinsics", snapshot.nativeRuntime().tlsProvider());
        assertEquals(true, snapshot.nativeRuntime().features().get("aes"));
        assertEquals(3, snapshot.rejectedConnections());
        assertEquals(1, snapshot.rejectedConnectionsByReason().get("global_limit"));
        assertEquals(2, snapshot.rejectedConnectionsByReason().get("per_address_limit"));
        assertEquals(1, snapshot.handshakeTimeouts());
        assertEquals(2, snapshot.packetAnomalies().get("malformed-varint"));
        assertEquals(2, snapshot.recentPacketAnomalies().size());
        assertEquals("127.0.0.1:50000", snapshot.recentPacketAnomalies().getFirst().remoteAddress());
        assertEquals("survival-1", snapshot.recentPacketAnomalies().getFirst().server());
        assertEquals("frontend_to_backend", snapshot.recentPacketAnomalies().getFirst().direction());
        assertEquals("HANDSHAKE", snapshot.recentPacketAnomalies().getFirst().protocolState());
        assertEquals(0, snapshot.recentPacketAnomalies().getFirst().packetId());
        assertEquals(64, snapshot.recentPacketAnomalies().getFirst().rawSize());
        assertEquals("bad frame", snapshot.recentPacketAnomalies().getFirst().detail());
        assertEquals(new ProxyMetrics.PacketTraffic(2, 96, 0), snapshot.packetTraffic().get(new ProxyMetrics.PacketTrafficKey(
                "survival-1",
                ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND,
                "UNCOMPRESSED",
                1)));
        var forgePayload = snapshot.customPayloads().get(new ProxyMetrics.CustomPayloadKey(
                "survival-1",
                ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND,
                "FORGE_HANDSHAKE",
                "fml:handshake"));
        assertEquals(2, forgePayload.packets());
        assertEquals(384, forgePayload.payloadBytes());
        assertEquals(0, forgePayload.compressedBytes());
        assertEquals(256, forgePayload.maxPayloadBytes());
        assertEquals(0, forgePayload.maxCompressedBytes());
        assertTrue(forgePayload.firstSeen() != null);
        assertTrue(forgePayload.lastSeen() != null);
        var unknownPayload = snapshot.customPayloads().get(new ProxyMetrics.CustomPayloadKey(
                "survival-1",
                ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND,
                "UNKNOWN",
                "unknown"));
        assertEquals(1, unknownPayload.packets());
        assertEquals(512, unknownPayload.payloadBytes());
        assertEquals(512, unknownPayload.maxPayloadBytes());
        assertEquals(3, snapshot.recentCustomPayloads().size());
        assertEquals("unknown", snapshot.recentCustomPayloads().getFirst().channel());
        assertEquals("survival-1", snapshot.recentCustomPayloads().getFirst().server());
        assertEquals("CONFIGURATION", snapshot.recentCustomPayloads().getFirst().protocolState());
        assertEquals(-1, snapshot.recentCustomPayloads().getFirst().packetId());
        var forgeHandshake = snapshot.forgeHandshakes().get(new ProxyMetrics.ForgeHandshakeKey(
                "survival-1",
                "Alex",
                "127.0.0.1:50000"));
        assertEquals("REGISTRY_DATA", forgeHandshake.stage());
        assertEquals("WAITING_SERVER_COMPLETE", forgeHandshake.clientPhase());
        assertEquals("REGISTRY_DATA_SENT", forgeHandshake.backendPhase());
        assertEquals(false, forgeHandshake.complete());
        assertEquals(true, forgeHandshake.backendSwitchBlocked());
        assertEquals(128, forgeHandshake.clientMods());
        assertEquals(130, forgeHandshake.serverMods());
        assertEquals(9, forgeHandshake.registryPackets());
        assertEquals(65536, forgeHandshake.registryBytes());
        assertTrue(forgeHandshake.updatedAt() != null);
        assertEquals(128, snapshot.frontendToBackendBytes());
        assertEquals(256, snapshot.backendToFrontendBytes());
        assertEquals(128, snapshot.serverTraffic().get("survival-1").frontendToBackendBytes());
        assertEquals(256, snapshot.serverTraffic().get("survival-1").backendToFrontendBytes());
        assertEquals(3, snapshot.compression().samples());
        assertEquals(1800, snapshot.compression().rawBytes());
        assertEquals(750, snapshot.compression().compressedBytes());
        assertEquals(1050, snapshot.compression().savedBytes());
        assertEquals(3_000_000, snapshot.compression().cpuNanos());
        assertEquals(750.0d / 1800.0d, snapshot.compression().ratio());
        assertEquals(1050, snapshot.serverCompression().get("survival-1").savedBytes());
        assertEquals(200, snapshot.serverCompressionByDirection().get(new ProxyMetrics.CompressionDirectionKey(
                "survival-1",
                ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND)).savedBytes());
        assertEquals(1, snapshot.compressionNegotiations());
        assertEquals(256, snapshot.serverCompressionThresholds().get("survival-1"));
        assertEquals(2, snapshot.compressionDecisions().get(new ProxyMetrics.CompressionDecisionKey(
                "survival-1",
                ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND,
                "threshold",
                1024)));
        assertEquals(new ProxyMetrics.RelayBackpressure(2, 256, 256), snapshot.relayBackpressure().get(new ProxyMetrics.RelayBackpressureKey(
                "survival-1",
                ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND)));
        assertEquals(1, snapshot.backendReplacements().get("attempted"));
        assertEquals(2, snapshot.backendReplacements().get("success"));
        assertEquals(2, snapshot.serverConnections().get("survival-1").routedConnections());
        assertEquals(1, snapshot.serverConnections().get("survival-1").activeConnections());
    }

    @Test
    void keepsOnlyMostRecentPlayerTransferSamples() {
        var metrics = new ProxyMetrics();
        for (var i = 0; i < 300; i++) {
            metrics.playerTransfer(
                    i % 2 == 0,
                    i % 2 == 0 ? "success" : "connect_failure",
                    "Player" + i,
                    "lobby-1",
                    "survival-" + i,
                    "127.0.0.1:" + (50000 + i));
        }

        var samples = metrics.snapshot().recentPlayerTransfers();

        assertEquals(256, samples.size());
        assertEquals("Player299", samples.getFirst().player());
        assertEquals("survival-299", samples.getFirst().targetServer());
        assertEquals("connect_failure", samples.getFirst().outcome());
        assertEquals("127.0.0.1:50299", samples.getFirst().remoteAddress());
        assertEquals("Player44", samples.getLast().player());
        assertTrue(samples.getFirst().sequence() > samples.getLast().sequence());
    }

    @Test
    void keepsOnlyMostRecentPacketAnomalySamples() {
        var metrics = new ProxyMetrics();
        for (var i = 0; i < 300; i++) {
            metrics.packetAnomaly("rule-" + i);
        }

        var samples = metrics.snapshot().recentPacketAnomalies();

        assertEquals(256, samples.size());
        assertEquals("rule-299", samples.getFirst().rule());
        assertEquals("rule-44", samples.getLast().rule());
        assertTrue(samples.getFirst().sequence() > samples.getLast().sequence());
    }

    @Test
    void keepsOnlyMostRecentCustomPayloadSamples() {
        var metrics = new ProxyMetrics();
        for (var i = 0; i < 300; i++) {
            metrics.customPayload(
                    "survival-1",
                    ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND,
                    "FABRIC_HANDSHAKE",
                    "fabric:registry/sync",
                    i,
                    0,
                    "Player" + i,
                    "127.0.0.1:" + (50000 + i),
                    "CONFIGURATION",
                    1);
        }

        var samples = metrics.snapshot().recentCustomPayloads();

        assertEquals(256, samples.size());
        assertEquals(299, samples.getFirst().payloadBytes());
        assertEquals("Player299", samples.getFirst().player());
        assertEquals("127.0.0.1:50299", samples.getFirst().remoteAddress());
        assertEquals(44, samples.getLast().payloadBytes());
        assertTrue(samples.getFirst().sequence() > samples.getLast().sequence());
    }

    @Test
    void removesClosedForgeHandshakeStateByConnectionKey() {
        var metrics = new ProxyMetrics();
        metrics.forgeHandshake(
                "survival-1",
                "Alex",
                "127.0.0.1:50000",
                "COMPLETE",
                "COMPLETE",
                "COMPLETE",
                true,
                false,
                1,
                1,
                1,
                128);
        metrics.forgeHandshake(
                "survival-1",
                "Steve",
                "127.0.0.1:50001",
                "REGISTRY_DATA",
                "WAITING_SERVER_COMPLETE",
                "REGISTRY_DATA_SENT",
                false,
                true,
                2,
                2,
                2,
                256);

        metrics.forgeHandshakeClosed("survival-1", "Alex", "127.0.0.1:50000");

        var snapshot = metrics.snapshot();
        assertEquals(1, snapshot.forgeHandshakes().size());
        assertTrue(snapshot.forgeHandshakes().containsKey(new ProxyMetrics.ForgeHandshakeKey(
                "survival-1",
                "Steve",
                "127.0.0.1:50001")));
    }

    @Test
    void canDisablePacketAnomalySamplingWithoutDisablingCounters() {
        var metrics = new ProxyMetrics(false);

        metrics.packetAnomaly("oversized-payload", "127.0.0.1:50000", "survival-1", "frontend_to_backend", "CONFIGURATION", 1, 2048, -1, "unknown:blob");

        var snapshot = metrics.snapshot();
        assertEquals(1, snapshot.packetAnomalies().get("oversized-payload"));
        assertTrue(snapshot.recentPacketAnomalies().isEmpty());
    }

    @Test
    void recordsBoundedPayloadCaptureSamples() {
        var metrics = new ProxyMetrics();
        metrics.startPayloadCapture(
                "cap-1",
                "survival-1",
                ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND,
                2,
                3,
                Instant.now().plusSeconds(30));

        var request = metrics.payloadCaptureRequest("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND);
        assertTrue(request.enabled());
        assertEquals(3, request.maxBytesPerSample());

        metrics.payloadCaptured("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, 10, -1, new byte[] {1, 2, 3, 4});
        metrics.payloadCaptured("survival-1", ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND, 11, -1, new byte[] {5, 6, 7, 8});
        metrics.payloadCaptured(
                "survival-1",
                ProxyMetrics.CompressionDirection.FRONTEND_TO_BACKEND,
                12,
                -1,
                new byte[] {9, 10, 11, 12},
                "Steve",
                "127.0.0.1:50000");

        var samples = metrics.payloadCaptureSamples("cap-1");
        assertEquals(2, samples.size());
        assertEquals(12, samples.getFirst().rawBytes());
        assertEquals(3, samples.getFirst().prefixBytes().length);
        assertEquals(9, samples.getFirst().prefixBytes()[0]);
        assertEquals("Steve", samples.getFirst().player());
        assertEquals("127.0.0.1:50000", samples.getFirst().remoteAddress());
        assertEquals(11, samples.getLast().rawBytes());

        assertTrue(metrics.stopPayloadCapture("cap-1"));
        assertTrue(metrics.payloadCaptures().isEmpty());
    }

    @Test
    void activeConnectionGaugesNeverGoBelowZero() {
        var metrics = new ProxyMetrics();

        metrics.closedConnection();
        metrics.serverConnectionClosed("survival-1");
        metrics.acceptedConnection();
        metrics.closedConnection();
        metrics.closedConnection();
        metrics.serverConnectionOpened("survival-1");
        metrics.serverConnectionClosed("survival-1");
        metrics.serverConnectionClosed("survival-1");

        var snapshot = metrics.snapshot();
        assertEquals(0, snapshot.activeConnections());
        assertEquals(0, snapshot.serverConnections().get("survival-1").activeConnections());
        assertEquals(1, snapshot.acceptedConnections());
        assertEquals(1, snapshot.serverConnections().get("survival-1").routedConnections());
    }

    @Test
    void tracksActivePlayerSessions() {
        var metrics = new ProxyMetrics();

        metrics.playerSessionStarted("Steve", "survival-1", "127.0.0.1:50000");

        var session = metrics.snapshot().playerSessions().get("Steve");
        assertEquals("Steve", session.player());
        assertEquals("survival-1", session.server());
        assertEquals("127.0.0.1:50000", session.remoteAddress());

        metrics.playerSessionClosed("Steve");

        assertTrue(metrics.snapshot().playerSessions().isEmpty());
    }
}

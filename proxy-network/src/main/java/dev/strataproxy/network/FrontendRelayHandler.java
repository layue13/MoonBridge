package dev.strataproxy.network;

import dev.strataproxy.analysis.CustomPayloadAnomalyPolicy;
import dev.strataproxy.analysis.AnomalyAction;
import dev.strataproxy.compression.CompressionAction;
import dev.strataproxy.observability.ProxyMetrics;
import dev.strataproxy.observability.ProxyMetrics.CompressionDirection;
import dev.strataproxy.analysis.PacketAnomaly;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

import java.util.ArrayList;
import java.util.List;

final class FrontendRelayHandler extends ChannelInboundHandlerAdapter {
    private static final String RULE_COMPRESSION_AUDIT_MALFORMED = "compression-audit-malformed";

    private final Channel backend;
    private final ProxyMetrics metrics;
    private final String serverName;
    private final MinecraftCompressionAuditState compressionAudit;
    private final CompressionRuntime compressionRuntime;
    private final MinecraftCustomPayloadInspectionSampler customPayloadInspection;
    private final MinecraftPacketTrafficSampler packetTrafficSampler;
    private final MinecraftLoginStartSampler loginStartSampler;
    private final int maxFrameBytes;
    private final CustomPayloadAnomalyPolicy customPayloadPolicy;
    private final RelaySessionIdentity identity;
    private final CompressionRewriteRuntime compressionRewriteRuntime;
    private MinecraftCompressedCustomPayloadInspectionSampler compressedCustomPayloadInspection;
    private String playerName;
    private boolean closed;

    FrontendRelayHandler(Channel backend, ProxyMetrics metrics, String serverName) {
        this(backend, metrics, serverName, new MinecraftCompressionAuditState(NetworkTuning.defaults().maxFrameBytes()));
    }

    FrontendRelayHandler(Channel backend, ProxyMetrics metrics, String serverName, MinecraftCompressionAuditState compressionAudit) {
        this(backend, metrics, serverName, compressionAudit, CompressionRuntime.defaults());
    }

    FrontendRelayHandler(
            Channel backend,
            ProxyMetrics metrics,
            String serverName,
            MinecraftCompressionAuditState compressionAudit,
            CompressionRuntime compressionRuntime) {
        this(backend, metrics, serverName, compressionAudit, compressionRuntime, NetworkTuning.defaults().maxFrameBytes());
    }

    FrontendRelayHandler(
            Channel backend,
            ProxyMetrics metrics,
            String serverName,
            MinecraftCompressionAuditState compressionAudit,
            CompressionRuntime compressionRuntime,
            int maxFrameBytes) {
        this(backend, metrics, serverName, compressionAudit, compressionRuntime, maxFrameBytes, CustomPayloadAnomalyPolicy.defaults());
    }

    FrontendRelayHandler(
            Channel backend,
            ProxyMetrics metrics,
            String serverName,
            MinecraftCompressionAuditState compressionAudit,
            CompressionRuntime compressionRuntime,
            int maxFrameBytes,
            CustomPayloadAnomalyPolicy customPayloadPolicy) {
        this(backend, metrics, serverName, compressionAudit, compressionRuntime, maxFrameBytes, customPayloadPolicy, null);
    }

    FrontendRelayHandler(
            Channel backend,
            ProxyMetrics metrics,
            String serverName,
            MinecraftCompressionAuditState compressionAudit,
            CompressionRuntime compressionRuntime,
            int maxFrameBytes,
            CustomPayloadAnomalyPolicy customPayloadPolicy,
            String initialPlayerName) {
        this(backend, metrics, serverName, compressionAudit, compressionRuntime, maxFrameBytes, customPayloadPolicy, initialPlayerName, new RelaySessionIdentity(""));
    }

    FrontendRelayHandler(
            Channel backend,
            ProxyMetrics metrics,
            String serverName,
            MinecraftCompressionAuditState compressionAudit,
            CompressionRuntime compressionRuntime,
            int maxFrameBytes,
            CustomPayloadAnomalyPolicy customPayloadPolicy,
            String initialPlayerName,
            RelaySessionIdentity identity) {
        this(backend, metrics, serverName, compressionAudit, compressionRuntime, maxFrameBytes, customPayloadPolicy, initialPlayerName, identity, false);
    }

    FrontendRelayHandler(
            Channel backend,
            ProxyMetrics metrics,
            String serverName,
            MinecraftCompressionAuditState compressionAudit,
            CompressionRuntime compressionRuntime,
            int maxFrameBytes,
            CustomPayloadAnomalyPolicy customPayloadPolicy,
            String initialPlayerName,
            RelaySessionIdentity identity,
            boolean compressionRewriteEnabled) {
        this(backend, metrics, serverName, compressionAudit, compressionRuntime, maxFrameBytes, customPayloadPolicy, initialPlayerName, identity, compressionRewriteEnabled, 25);
    }

    FrontendRelayHandler(
            Channel backend,
            ProxyMetrics metrics,
            String serverName,
            MinecraftCompressionAuditState compressionAudit,
            CompressionRuntime compressionRuntime,
            int maxFrameBytes,
            CustomPayloadAnomalyPolicy customPayloadPolicy,
            String initialPlayerName,
            RelaySessionIdentity identity,
            boolean compressionRewriteEnabled,
            int compressionRewriteMaxEventLoopDelayMillis) {
        this.backend = backend;
        this.metrics = metrics;
        this.serverName = serverName;
        this.compressionAudit = compressionAudit;
        this.compressionRuntime = compressionRuntime;
        this.maxFrameBytes = maxFrameBytes;
        this.customPayloadPolicy = customPayloadPolicy;
        this.customPayloadInspection = new MinecraftCustomPayloadInspectionSampler(
                maxFrameBytes,
                Math.min(maxFrameBytes, customPayloadPolicy.largePayloadWarnBytes()),
                customPayloadPolicy);
        this.packetTrafficSampler = new MinecraftPacketTrafficSampler(maxFrameBytes);
        this.loginStartSampler = new MinecraftLoginStartSampler(maxFrameBytes);
        this.identity = identity;
        this.compressionRewriteRuntime = new CompressionRewriteRuntime(compressionRewriteEnabled, compressionRewriteMaxEventLoopDelayMillis);
        this.playerName = initialPlayerName;
        if (initialPlayerName != null && !initialPlayerName.isBlank()) {
            this.identity.playerName(initialPlayerName);
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        if (!backend.isActive()) {
            ReferenceCountUtil.release(message);
            context.close();
            return;
        }
        var outbound = message;
        var forward = true;
        List<CompressionAction> compressionActions = List.of();
        if (message instanceof ByteBuf buffer) {
            metrics.frontendToBackendBytes(serverName, buffer.readableBytes());
            capturePayloadPrefix(buffer, CompressionDirection.FRONTEND_TO_BACKEND);
            observeLoginStart(context, buffer);
            observePacketTraffic(buffer);
            var compression = observeCompressionFrames(buffer);
            forward = observeCustomPayload(context, buffer) && compression.shouldForward();
            compressionActions = compression.actions();
            if (forward && compression.rewriteEligible()) {
                var rewrite = compressionRewriteRuntime.rewrite(
                        context.alloc(),
                        metrics,
                        serverName,
                        CompressionDirection.FRONTEND_TO_BACKEND,
                        buffer,
                        compressionAudit.threshold(),
                        compressionActions,
                        maxFrameBytes);
                if (rewrite.suppressed()) {
                    ReferenceCountUtil.release(message);
                    context.channel().read();
                    return;
                }
                if (rewrite.replaced()) {
                    ReferenceCountUtil.release(message);
                    outbound = rewrite.frame();
                }
            }
        }
        if (!forward) {
            ReferenceCountUtil.release(outbound);
            context.close();
            closeBackend();
            return;
        }
        recordBackpressureIfNeeded(backend, CompressionDirection.FRONTEND_TO_BACKEND);
        backend.writeAndFlush(outbound).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                context.channel().read();
            } else {
                future.channel().close();
                context.close();
            }
        });
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        closeFrontendSide();
        closeBackend();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        context.close();
        closeFrontendSide();
        closeBackend();
    }

    private void closeFrontendSide() {
        if (closed) {
            return;
        }
        closed = true;
        compressionAudit.closeFrontendSampler();
        customPayloadInspection.close();
        loginStartSampler.close();
        if (compressedCustomPayloadInspection != null) {
            compressedCustomPayloadInspection.close();
        }
        packetTrafficSampler.close();
        compressionRewriteRuntime.close();
        metrics.playerSessionClosed(playerName);
        metrics.serverConnectionClosed(serverName);
    }

    private void closeBackend() {
        if (backend.isOpen()) {
            backend.close();
        }
    }

    private CompressionObservation observeCompressionFrames(ByteBuf buffer) {
        if (!compressionAudit.negotiated()) {
            return CompressionObservation.allow();
        }
        try {
            var actions = new ArrayList<CompressionAction>();
            for (var sample : compressionAudit.observeFrontend(buffer)) {
                metrics.compressionSample(
                        serverName,
                        CompressionDirection.FRONTEND_TO_BACKEND,
                        sample.rawBytes(),
                        sample.compressedBytes(),
                        0);
                actions.add(compressionRuntime.recordDecision(
                        metrics,
                        serverName,
                        CompressionDirection.FRONTEND_TO_BACKEND,
                        sample.rawBytes(),
                        ratio(sample),
                        0));
            }
            return new CompressionObservation(true, true, List.copyOf(actions));
        } catch (RuntimeException exception) {
            metrics.packetAnomaly(
                    RULE_COMPRESSION_AUDIT_MALFORMED,
                    "",
                    serverName,
                    CompressionDirection.FRONTEND_TO_BACKEND.label(),
                    "PLAY",
                    -1,
                    buffer.readableBytes(),
                    -1,
                    exception.getMessage());
            compressionAudit.closeFrontendSampler();
            return CompressionObservation.block();
        }
    }

    private boolean observeCustomPayload(ChannelHandlerContext context, ByteBuf buffer) {
        if (compressionAudit.negotiated()) {
            customPayloadInspection.close();
            return observeCompressedCustomPayload(context, buffer);
        }
        try {
            for (var result : customPayloadInspection.observe(buffer)) {
                recordCustomPayload(result.classification(), -1);
                for (var anomaly : result.anomalies()) {
                    recordCustomPayloadAnomaly(context, result.classification(), anomaly, -1);
                    if (anomaly.action() == AnomalyAction.THROTTLE) {
                        customPayloadInspection.close();
                        return false;
                    }
                }
            }
            return true;
        } catch (RuntimeException exception) {
            metrics.packetAnomaly(
                    "custom-payload-inspection-malformed",
                    remoteAddress(context),
                    serverName,
                    CompressionDirection.FRONTEND_TO_BACKEND.label(),
                    "CONFIGURATION",
                    -1,
                    buffer.readableBytes(),
                    -1,
                    exception.getMessage());
            customPayloadInspection.close();
            return true;
        }
    }

    private boolean observeCompressedCustomPayload(ChannelHandlerContext context, ByteBuf buffer) {
        if (compressedCustomPayloadInspection == null) {
            compressedCustomPayloadInspection = new MinecraftCompressedCustomPayloadInspectionSampler(
                    compressionAudit.threshold(),
                    maxFrameBytes,
                    Math.min(maxFrameBytes, customPayloadPolicy.largePayloadWarnBytes()),
                    customPayloadPolicy);
        }
        try {
            for (var result : compressedCustomPayloadInspection.observe(buffer)) {
                recordCustomPayload(result.classification(), result.compressedBytes());
                for (var anomaly : result.anomalies()) {
                    recordCustomPayloadAnomaly(context, result.classification(), anomaly, result.compressedBytes());
                    if (anomaly.action() == AnomalyAction.THROTTLE) {
                        compressedCustomPayloadInspection.close();
                        return false;
                    }
                }
            }
            return true;
        } catch (RuntimeException exception) {
            metrics.packetAnomaly(
                    "compressed-custom-payload-inspection-malformed",
                    remoteAddress(context),
                    serverName,
                    CompressionDirection.FRONTEND_TO_BACKEND.label(),
                    "CONFIGURATION",
                    -1,
                    buffer.readableBytes(),
                    -1,
                    exception.getMessage());
            compressedCustomPayloadInspection.close();
            return true;
        }
    }

    private void observePacketTraffic(ByteBuf buffer) {
        if (compressionAudit.negotiated()) {
            packetTrafficSampler.close();
            return;
        }
        try {
            for (var sample : packetTrafficSampler.observe(buffer)) {
                metrics.packetTraffic(
                        serverName,
                        CompressionDirection.FRONTEND_TO_BACKEND,
                        "UNCOMPRESSED",
                        sample.packetId(),
                        sample.frameBytes(),
                        0);
            }
        } catch (RuntimeException exception) {
            packetTrafficSampler.close();
        }
    }

    private void observeLoginStart(ChannelHandlerContext context, ByteBuf buffer) {
        if (playerName != null || compressionAudit.negotiated()) {
            loginStartSampler.close();
            return;
        }
        try {
            var username = loginStartSampler.observe(buffer);
            if (username.isPresent()) {
                playerName = username.get();
                identity.playerName(playerName);
                metrics.playerSessionStarted(playerName, serverName, remoteAddress(context));
            }
        } catch (RuntimeException exception) {
            loginStartSampler.close();
        }
    }

    private void recordCustomPayloadAnomaly(
            ChannelHandlerContext context,
            dev.strataproxy.codec.minecraft.MinecraftCustomPayloadClassifier.CustomPayloadClassification classification,
            PacketAnomaly anomaly,
            long compressedSize) {
        metrics.packetAnomaly(
                anomaly.ruleId(),
                remoteAddress(context),
                serverName,
                CompressionDirection.FRONTEND_TO_BACKEND.label(),
                "CONFIGURATION",
                classification.packetId(),
                anomaly.packet().rawSize(),
                compressedSize,
                classification.channel() + " kind=" + classification.kind() + " action=" + anomaly.action());
    }

    private void recordCustomPayload(
            dev.strataproxy.codec.minecraft.MinecraftCustomPayloadClassifier.CustomPayloadClassification classification,
            long compressedSize) {
        metrics.customPayload(
                serverName,
                CompressionDirection.FRONTEND_TO_BACKEND,
                classification.kind().name(),
                classification.channel(),
                classification.payloadBytes(),
                Math.max(0, compressedSize));
    }

    private void recordBackpressureIfNeeded(Channel target, CompressionDirection direction) {
        if (!target.isWritable()) {
            metrics.relayBackpressure(serverName, direction, target.bytesBeforeWritable());
        }
    }

    private void capturePayloadPrefix(ByteBuf buffer, CompressionDirection direction) {
        var request = metrics.payloadCaptureRequest(serverName, direction);
        if (!request.enabled()) {
            return;
        }
        var bytes = new byte[Math.min(buffer.readableBytes(), request.maxBytesPerSample())];
        buffer.getBytes(buffer.readerIndex(), bytes);
        metrics.payloadCaptured(
                serverName,
                direction,
                buffer.readableBytes(),
                compressionAudit.negotiated() ? buffer.readableBytes() : -1,
                bytes,
                identity.playerName(),
                identity.remoteAddress());
    }

    private static String remoteAddress(ChannelHandlerContext context) {
        var address = context.channel().remoteAddress();
        return address == null ? "" : address.toString();
    }

    private static double ratio(MinecraftCompressedFrameAuditSampler.CompressionFrameSample sample) {
        return sample.rawBytes() == 0 ? 1.0d : (double) sample.compressedBytes() / sample.rawBytes();
    }

    private record CompressionObservation(boolean shouldForward, boolean rewriteEligible, List<CompressionAction> actions) {
        private static CompressionObservation allow() {
            return new CompressionObservation(true, false, List.of());
        }

        private static CompressionObservation block() {
            return new CompressionObservation(false, false, List.of());
        }
    }
}

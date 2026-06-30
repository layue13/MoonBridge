package dev.strataproxy.network;

import dev.strataproxy.compression.CompressionAction;
import dev.strataproxy.observability.ProxyMetrics;
import dev.strataproxy.observability.ProxyMetrics.CompressionDirection;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

import java.util.ArrayList;
import java.util.List;

final class BackendRelayHandler extends ChannelInboundHandlerAdapter {
    private static final String RULE_COMPRESSION_NEGOTIATION_MALFORMED = "compression-negotiation-malformed";
    private static final String RULE_COMPRESSION_AUDIT_MALFORMED = "compression-audit-malformed";

    private final Channel frontend;
    private final ProxyMetrics metrics;
    private final String serverName;
    private final MinecraftCompressionAuditState compressionAudit;
    private final MinecraftCompressionNegotiationDetector compressionDetector;
    private final CompressionRuntime compressionRuntime;
    private final MinecraftPacketTrafficSampler packetTrafficSampler;
    private final RelaySessionIdentity identity;
    private final MinecraftForwardingRuntime forwardingRuntime;
    private final CompressionRewriteRuntime compressionRewriteRuntime;
    private final int maxFrameBytes;
    private boolean closed;

    BackendRelayHandler(Channel frontend, ProxyMetrics metrics, String serverName) {
        this(frontend, metrics, serverName, NetworkTuning.defaults().maxFrameBytes());
    }

    BackendRelayHandler(Channel frontend, ProxyMetrics metrics, String serverName, int maxFrameBytes) {
        this(frontend, metrics, serverName, maxFrameBytes, new MinecraftCompressionAuditState(maxFrameBytes));
    }

    BackendRelayHandler(
            Channel frontend,
            ProxyMetrics metrics,
            String serverName,
            int maxFrameBytes,
            MinecraftCompressionAuditState compressionAudit) {
        this(frontend, metrics, serverName, maxFrameBytes, compressionAudit, CompressionRuntime.defaults());
    }

    BackendRelayHandler(
            Channel frontend,
            ProxyMetrics metrics,
            String serverName,
            int maxFrameBytes,
            MinecraftCompressionAuditState compressionAudit,
            CompressionRuntime compressionRuntime) {
        this(frontend, metrics, serverName, maxFrameBytes, compressionAudit, compressionRuntime, new RelaySessionIdentity(""));
    }

    BackendRelayHandler(
            Channel frontend,
            ProxyMetrics metrics,
            String serverName,
            int maxFrameBytes,
            MinecraftCompressionAuditState compressionAudit,
            CompressionRuntime compressionRuntime,
            RelaySessionIdentity identity) {
        this(frontend, metrics, serverName, maxFrameBytes, compressionAudit, compressionRuntime, identity, false);
    }

    BackendRelayHandler(
            Channel frontend,
            ProxyMetrics metrics,
            String serverName,
            int maxFrameBytes,
            MinecraftCompressionAuditState compressionAudit,
            CompressionRuntime compressionRuntime,
            RelaySessionIdentity identity,
            boolean compressionRewriteEnabled) {
        this(frontend, metrics, serverName, maxFrameBytes, compressionAudit, compressionRuntime, identity, compressionRewriteEnabled, 25);
    }

    BackendRelayHandler(
            Channel frontend,
            ProxyMetrics metrics,
            String serverName,
            int maxFrameBytes,
            MinecraftCompressionAuditState compressionAudit,
            CompressionRuntime compressionRuntime,
            RelaySessionIdentity identity,
            boolean compressionRewriteEnabled,
            int compressionRewriteMaxEventLoopDelayMillis) {
        this(frontend, metrics, serverName, maxFrameBytes, compressionAudit, compressionRuntime, identity, MinecraftForwardingRuntime.none(), compressionRewriteEnabled, compressionRewriteMaxEventLoopDelayMillis);
    }

    BackendRelayHandler(
            Channel frontend,
            ProxyMetrics metrics,
            String serverName,
            int maxFrameBytes,
            MinecraftCompressionAuditState compressionAudit,
            CompressionRuntime compressionRuntime,
            RelaySessionIdentity identity,
            MinecraftForwardingRuntime forwardingRuntime,
            boolean compressionRewriteEnabled,
            int compressionRewriteMaxEventLoopDelayMillis) {
        this.frontend = frontend;
        this.metrics = metrics;
        this.serverName = serverName;
        this.compressionAudit = compressionAudit;
        this.compressionDetector = new MinecraftCompressionNegotiationDetector(maxFrameBytes);
        this.compressionRuntime = compressionRuntime;
        this.packetTrafficSampler = new MinecraftPacketTrafficSampler(maxFrameBytes);
        this.identity = identity;
        this.forwardingRuntime = forwardingRuntime == null ? MinecraftForwardingRuntime.none() : forwardingRuntime;
        this.compressionRewriteRuntime = new CompressionRewriteRuntime(compressionRewriteEnabled, compressionRewriteMaxEventLoopDelayMillis);
        this.maxFrameBytes = maxFrameBytes;
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        if (!frontend.isActive()) {
            ReferenceCountUtil.release(message);
            context.close();
            return;
        }
        var outbound = message;
        var forward = true;
        List<CompressionAction> compressionActions = List.of();
        if (message instanceof ByteBuf buffer) {
            var forwardingRequest = observeVelocityForwardingRequest(context, buffer);
            if (forwardingRequest.matched()) {
                ReferenceCountUtil.release(message);
                var response = VelocityModernForwarding.response(context.alloc(), forwardingRequest.messageId(), forwardingRuntime, identity);
                context.writeAndFlush(response).addListener((ChannelFutureListener) future -> {
                    if (future.isSuccess()) {
                        context.channel().read();
                    } else {
                        context.close();
                        if (frontend.isOpen()) {
                            frontend.close();
                        }
                    }
                });
                return;
            }
            metrics.backendToFrontendBytes(serverName, buffer.readableBytes());
            capturePayloadPrefix(buffer, CompressionDirection.BACKEND_TO_FRONTEND);
            observePacketTraffic(buffer);
            var compression = observeCompression(buffer);
            forward = compression.shouldForward();
            compressionActions = compression.actions();
            if (forward && compression.rewriteEligible()) {
                var rewrite = compressionRewriteRuntime.rewrite(
                        context.alloc(),
                        metrics,
                        serverName,
                        CompressionDirection.BACKEND_TO_FRONTEND,
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
            if (frontend.isOpen()) {
                frontend.close();
            }
            return;
        }
        recordBackpressureIfNeeded(frontend);
        frontend.writeAndFlush(outbound).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                context.channel().read();
            } else {
                future.channel().close();
                context.close();
            }
        });
    }

    private VelocityModernForwarding.ForwardingRequest observeVelocityForwardingRequest(ChannelHandlerContext context, ByteBuf buffer) {
        if (!forwardingRuntime.velocityModern() || compressionAudit.negotiated()) {
            return VelocityModernForwarding.ForwardingRequest.none();
        }
        return VelocityModernForwarding.request(buffer, maxFrameBytes);
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        closeBackendSide();
        if (frontend.isOpen()) {
            frontend.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        context.close();
        closeBackendSide();
        if (frontend.isOpen()) {
            frontend.close();
        }
    }

    private void closeBackendSide() {
        if (closed) {
            return;
        }
        closed = true;
        compressionDetector.close();
        compressionAudit.closeBackendSampler();
        packetTrafficSampler.close();
        compressionRewriteRuntime.close();
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
                        CompressionDirection.BACKEND_TO_FRONTEND,
                        "UNCOMPRESSED",
                        sample.packetId(),
                        sample.frameBytes(),
                        0);
            }
        } catch (RuntimeException exception) {
            packetTrafficSampler.close();
        }
    }

    private CompressionObservation observeCompression(ByteBuf buffer) {
        if (compressionAudit.negotiated()) {
            return observeCompressionFrames(buffer);
        }
        try {
            var threshold = compressionDetector.observe(buffer);
            if (threshold.isPresent()) {
                metrics.compressionNegotiated(serverName, threshold.getAsInt());
                compressionAudit.negotiate(threshold.getAsInt());
            }
            return CompressionObservation.allow();
        } catch (RuntimeException exception) {
            metrics.packetAnomaly(
                    RULE_COMPRESSION_NEGOTIATION_MALFORMED,
                    "",
                    serverName,
                    CompressionDirection.BACKEND_TO_FRONTEND.label(),
                    "LOGIN",
                    -1,
                    buffer.readableBytes(),
                    -1,
                    exception.getMessage());
            return CompressionObservation.block();
        }
    }

    private CompressionObservation observeCompressionFrames(ByteBuf buffer) {
        try {
            var actions = new ArrayList<CompressionAction>();
            for (var sample : compressionAudit.observeBackend(buffer)) {
                metrics.compressionSample(
                        serverName,
                        CompressionDirection.BACKEND_TO_FRONTEND,
                        sample.rawBytes(),
                        sample.compressedBytes(),
                        0);
                actions.add(compressionRuntime.recordDecision(
                        metrics,
                        serverName,
                        CompressionDirection.BACKEND_TO_FRONTEND,
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
                    CompressionDirection.BACKEND_TO_FRONTEND.label(),
                    "PLAY",
                    -1,
                    buffer.readableBytes(),
                    -1,
                    exception.getMessage());
            compressionAudit.closeBackendSampler();
            return CompressionObservation.block();
        }
    }

    private static double ratio(MinecraftCompressedFrameAuditSampler.CompressionFrameSample sample) {
        return sample.rawBytes() == 0 ? 1.0d : (double) sample.compressedBytes() / sample.rawBytes();
    }

    private void recordBackpressureIfNeeded(Channel target) {
        if (!target.isWritable()) {
            metrics.relayBackpressure(serverName, CompressionDirection.BACKEND_TO_FRONTEND, target.bytesBeforeWritable());
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

    private record CompressionObservation(boolean shouldForward, boolean rewriteEligible, List<CompressionAction> actions) {
        private static CompressionObservation allow() {
            return new CompressionObservation(true, false, List.of());
        }

        private static CompressionObservation block() {
            return new CompressionObservation(false, false, List.of());
        }
    }
}

package dev.strataproxy.network;

import dev.strataproxy.network.MinecraftCompressionCodec;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

final class BackendSwitchLoginHandler extends ByteToMessageDecoder {
    private final dev.strataproxy.network.ProxyMetrics metrics;
    private final String serverName;
    private final int maxFrameBytes;
    private final MinecraftCompressionAuditState compressionAudit;
    private final MinecraftForwardingRuntime forwardingRuntime;
    private final RelaySessionIdentity identity;
    private final MinecraftProtocolProfile profile;
    private final Listener listener;
    private final MinecraftCompressionCodec compressionCodec = new MinecraftCompressionCodec();
    private boolean terminal;
    private boolean closed;

    BackendSwitchLoginHandler(
            dev.strataproxy.network.ProxyMetrics metrics,
            String serverName,
            int maxFrameBytes,
            MinecraftCompressionAuditState compressionAudit,
            MinecraftForwardingRuntime forwardingRuntime,
            RelaySessionIdentity identity,
            int protocolVersion,
            Listener listener) {
        this(
                metrics,
                serverName,
                maxFrameBytes,
                compressionAudit,
                forwardingRuntime,
                identity,
                MinecraftProtocolProfile.forVersion(protocolVersion),
                null,
                listener);
    }

    BackendSwitchLoginHandler(
            dev.strataproxy.network.ProxyMetrics metrics,
            String serverName,
            int maxFrameBytes,
            MinecraftCompressionAuditState compressionAudit,
            MinecraftForwardingRuntime forwardingRuntime,
            RelaySessionIdentity identity,
            MinecraftProtocolProfile profile,
            Listener listener) {
        this(
                metrics,
                serverName,
                maxFrameBytes,
                compressionAudit,
                forwardingRuntime,
                identity,
                profile,
                null,
                listener);
    }

    BackendSwitchLoginHandler(
            dev.strataproxy.network.ProxyMetrics metrics,
            String serverName,
            int maxFrameBytes,
            MinecraftCompressionAuditState compressionAudit,
            MinecraftForwardingRuntime forwardingRuntime,
            RelaySessionIdentity identity,
            MinecraftProtocolProfile profile,
            MinecraftForgeHandshakeTracker forgeHandshakeTracker,
            Listener listener) {
        this.metrics = metrics;
        this.serverName = serverName;
        this.maxFrameBytes = maxFrameBytes;
        this.compressionAudit = compressionAudit;
        this.forwardingRuntime = forwardingRuntime == null ? MinecraftForwardingRuntime.none() : forwardingRuntime;
        this.identity = identity;
        this.profile = profile == null
                ? MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_20_1)
                : profile;
        this.listener = listener;
    }

    @Override
    protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> output) {
        if (terminal) {
            return;
        }
        while (input.isReadable()) {
            var probe = MinecraftProtocolCodec.probeFrame(input, maxFrameBytes);
            if (!probe.complete()) {
                return;
            }
            var frame = input.readRetainedSlice(probe.totalBytes());
            try {
                var action = handleLoginFrame(context, frame);
                if (action == FrameAction.LOGIN_SUCCEEDED) {
                    terminal = true;
                    listener.backendLoginReady(context.channel(), null, remaining(input));
                    return;
                }
            } catch (RuntimeException exception) {
                fail(context, "backend_login_malformed");
                return;
            } finally {
                frame.release();
            }
            input.discardReadBytes();
        }
    }

    @Override
    /** Provides exception caught. */
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        fail(context, "backend_login_exception");
    }

    @Override
    /** Provides channel inactive. */
    public void channelInactive(ChannelHandlerContext context) {
        if (!terminal) {
            listener.backendLoginFailed(context.channel(), "backend_login_closed");
        }
        closeCodec();
    }

    @Override
    protected void handlerRemoved0(ChannelHandlerContext context) {
        closeCodec();
    }

    private FrameAction handleLoginFrame(ChannelHandlerContext context, ByteBuf frame) {
        var packet = decodePacket(context, frame);
        try {
            var packetId = MinecraftProtocolCodec.readVarInt(packet);
            if (profile.clientboundLoginSetCompressionPacketId().isPresent()
                    && packetId == profile.clientboundLoginSetCompressionPacketId().getAsInt()
                    && !compressionAudit.negotiated()) {
                if (profile.compressionNegotiationSupported()) {
                    var threshold = MinecraftProtocolCodec.readVarInt(packet);
                    compressionAudit.negotiate(threshold);
                    metrics.compressionNegotiated(serverName, threshold);
                    return FrameAction.WAIT;
                }
            }
            if (packetId == profile.clientboundLoginSuccessPacketId()) {
                return FrameAction.LOGIN_SUCCEEDED;
            }
            if (packetId == profile.clientboundLoginDisconnectPacketId()) {
                fail(context, "backend_login_disconnect");
                return FrameAction.WAIT;
            }
            if (packetId == profile.clientboundLoginEncryptionRequestPacketId()) {
                fail(context, "backend_login_encryption_request");
                return FrameAction.WAIT;
            }
            if (handleVelocityForwarding(context, frame)) {
                return FrameAction.WAIT;
            }
            return FrameAction.WAIT;
        } finally {
            packet.release();
        }
    }

    private boolean handleVelocityForwarding(ChannelHandlerContext context, ByteBuf frame) {
        if (!forwardingRuntime.velocityModern() || compressionAudit.negotiated()) {
            return false;
        }
        var request = VelocityModernForwarding.request(frame, maxFrameBytes);
        if (!request.matched()) {
            return false;
        }
        var response = VelocityModernForwarding.response(
                context.alloc(),
                request.messageId(),
                request.version(),
                forwardingRuntime,
                identity);
        context.writeAndFlush(response).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                context.channel().read();
            } else {
                fail(context, "backend_login_forwarding_failure");
            }
        });
        return true;
    }

    private ByteBuf decodePacket(ChannelHandlerContext context, ByteBuf frame) {
        if (compressionAudit.negotiated()) {
            var duplicate = frame.retainedDuplicate();
            try {
                return compressionCodec.decodeFrame(context.alloc(), duplicate, compressionAudit.threshold(), maxFrameBytes);
            } finally {
                duplicate.release();
            }
        }
        var duplicate = frame.retainedDuplicate();
        try {
            var length = MinecraftProtocolCodec.readVarInt(duplicate);
            return duplicate.readRetainedSlice(length);
        } finally {
            duplicate.release();
        }
    }

    private void fail(ChannelHandlerContext context, String outcome) {
        if (terminal) {
            return;
        }
        terminal = true;
        listener.backendLoginFailed(context.channel(), outcome);
        context.close();
    }

    private void closeCodec() {
        if (!closed) {
            closed = true;
            compressionCodec.close();
        }
    }

    private ByteBuf remaining(ByteBuf input) {
        return input.isReadable() ? input.readRetainedSlice(input.readableBytes()) : null;
    }

    interface Listener {
        void backendLoginReady(io.netty.channel.Channel backend, ByteBuf clientboundFrames, ByteBuf remainingBackendFrames);

        void backendLoginFailed(io.netty.channel.Channel backend, String outcome);
    }

    private enum FrameAction {
        WAIT,
        LOGIN_SUCCEEDED
    }
}

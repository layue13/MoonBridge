package dev.strataproxy.network;

import dev.strataproxy.codec.minecraft.MinecraftCompressionCodec;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

final class BackendSwitchLoginHandler extends ByteToMessageDecoder {
    private static final int CLIENTBOUND_LOGIN_DISCONNECT_PACKET_ID = 0x00;
    private static final int CLIENTBOUND_LOGIN_ENCRYPTION_REQUEST_PACKET_ID = 0x01;
    private static final int CLIENTBOUND_LOGIN_SUCCESS_PACKET_ID = 0x02;
    private static final int CLIENTBOUND_LOGIN_SET_COMPRESSION_PACKET_ID = 0x03;

    private final dev.strataproxy.observability.ProxyMetrics metrics;
    private final String serverName;
    private final int maxFrameBytes;
    private final MinecraftCompressionAuditState compressionAudit;
    private final MinecraftForwardingRuntime forwardingRuntime;
    private final RelaySessionIdentity identity;
    private final Listener listener;
    private final MinecraftCompressionCodec compressionCodec = new MinecraftCompressionCodec();
    private boolean terminal;
    private boolean closed;

    BackendSwitchLoginHandler(
            dev.strataproxy.observability.ProxyMetrics metrics,
            String serverName,
            int maxFrameBytes,
            MinecraftCompressionAuditState compressionAudit,
            MinecraftForwardingRuntime forwardingRuntime,
            RelaySessionIdentity identity,
            Listener listener) {
        this.metrics = metrics;
        this.serverName = serverName;
        this.maxFrameBytes = maxFrameBytes;
        this.compressionAudit = compressionAudit;
        this.forwardingRuntime = forwardingRuntime == null ? MinecraftForwardingRuntime.none() : forwardingRuntime;
        this.identity = identity;
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
                if (handleFrame(context, frame)) {
                    terminal = true;
                    listener.backendLoginReady(context.channel());
                    if (input.isReadable()) {
                        context.fireChannelRead(input.readRetainedSlice(input.readableBytes()));
                    }
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

    private boolean handleFrame(ChannelHandlerContext context, ByteBuf frame) {
        var packet = decodePacket(context, frame);
        try {
            var packetId = MinecraftProtocolCodec.readVarInt(packet);
            if (packetId == CLIENTBOUND_LOGIN_SET_COMPRESSION_PACKET_ID && !compressionAudit.negotiated()) {
                var threshold = MinecraftProtocolCodec.readVarInt(packet);
                compressionAudit.negotiate(threshold);
                metrics.compressionNegotiated(serverName, threshold);
                return false;
            }
            if (packetId == CLIENTBOUND_LOGIN_SUCCESS_PACKET_ID) {
                return true;
            }
            if (packetId == CLIENTBOUND_LOGIN_DISCONNECT_PACKET_ID) {
                fail(context, "backend_login_disconnect");
                return false;
            }
            if (packetId == CLIENTBOUND_LOGIN_ENCRYPTION_REQUEST_PACKET_ID) {
                fail(context, "backend_login_encryption_request");
                return false;
            }
            if (handleVelocityForwarding(context, frame)) {
                return false;
            }
            return false;
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

    interface Listener {
        void backendLoginReady(io.netty.channel.Channel backend);

        void backendLoginFailed(io.netty.channel.Channel backend, String outcome);
    }
}

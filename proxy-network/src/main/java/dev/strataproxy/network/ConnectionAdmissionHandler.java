package dev.strataproxy.network;

import dev.strataproxy.observability.ProxyMetrics;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

final class ConnectionAdmissionHandler extends ChannelInboundHandlerAdapter {
    private final ConnectionAdmissionControl admissionControl;
    private final ProxyMetrics metrics;
    private final boolean waitForProxyProtocol;
    private ConnectionAdmissionControl.Admission admission;
    private boolean released;
    private boolean activeFired;

    ConnectionAdmissionHandler(ConnectionAdmissionControl admissionControl, ProxyMetrics metrics) {
        this(admissionControl, metrics, false);
    }

    ConnectionAdmissionHandler(ConnectionAdmissionControl admissionControl, ProxyMetrics metrics, boolean waitForProxyProtocol) {
        this.admissionControl = admissionControl;
        this.metrics = metrics;
        this.waitForProxyProtocol = waitForProxyProtocol;
    }

    @Override
    public void channelActive(ChannelHandlerContext context) throws Exception {
        if (waitForProxyProtocol) {
            context.read();
            return;
        }
        acquire(context, context.channel().remoteAddress());
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext context, Object event) throws Exception {
        if (event == ProxyProtocolV1Handler.ProxyProtocolReady.INSTANCE) {
            acquire(context, ClientAddress.socketAddress(context.channel()));
            return;
        }
        super.userEventTriggered(context, event);
    }

    private void acquire(ChannelHandlerContext context, java.net.SocketAddress address) throws Exception {
        if (admission != null) {
            return;
        }
        admission = admissionControl.acquire(address);
        if (!admission.accepted()) {
            metrics.rejectedConnection(admission.rejectionReason());
            context.close();
            return;
        }
        metrics.acceptedConnection();
        if (!activeFired) {
            activeFired = true;
            super.channelActive(context);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        if (!released) {
            released = true;
            admissionControl.release(admission);
            if (admission != null && admission.accepted()) {
                metrics.closedConnection();
            }
        }
        super.channelInactive(context);
    }
}

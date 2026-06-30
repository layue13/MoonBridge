package dev.strataproxy.network;

import dev.strataproxy.observability.ProxyMetrics;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

final class ConnectionAdmissionHandler extends ChannelInboundHandlerAdapter {
    private final ConnectionAdmissionControl admissionControl;
    private final ProxyMetrics metrics;
    private ConnectionAdmissionControl.Admission admission;
    private boolean released;

    ConnectionAdmissionHandler(ConnectionAdmissionControl admissionControl, ProxyMetrics metrics) {
        this.admissionControl = admissionControl;
        this.metrics = metrics;
    }

    @Override
    public void channelActive(ChannelHandlerContext context) throws Exception {
        admission = admissionControl.acquire(context.channel().remoteAddress());
        if (!admission.accepted()) {
            metrics.rejectedConnection(admission.rejectionReason());
            context.close();
            return;
        }
        metrics.acceptedConnection();
        super.channelActive(context);
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

package dev.strataproxy.network;

import dev.strataproxy.observability.ProxyMetrics;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.ReadTimeoutException;

import java.util.concurrent.TimeUnit;

final class InitialHandshakeTimeoutHandler extends ReadTimeoutHandler {
    private final ProxyMetrics metrics;

    InitialHandshakeTimeoutHandler(int timeoutMillis, ProxyMetrics metrics) {
        super(timeoutMillis, TimeUnit.MILLISECONDS);
        this.metrics = metrics;
    }

    @Override
    protected void readTimedOut(ChannelHandlerContext context) {
        metrics.handshakeTimeout();
        context.close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) throws Exception {
        if (cause instanceof ReadTimeoutException) {
            metrics.handshakeTimeout();
            context.close();
            return;
        }
        super.exceptionCaught(context, cause);
    }
}

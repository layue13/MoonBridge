package dev.strataproxy.network;

import dev.strataproxy.observability.ProxyMetrics;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ConnectionAdmissionHandlerTest {
    @Test
    void acceptedConnectionIsReleasedOnlyOnce() {
        var control = new ConnectionAdmissionControl(1, 10);
        var metrics = new ProxyMetrics();
        var channel = new EmbeddedChannel(new ConnectionAdmissionHandler(control, metrics));

        assertEquals(1, control.activeConnections());
        assertEquals(1, metrics.snapshot().activeConnections());

        channel.close();
        channel.pipeline().fireChannelInactive();

        assertEquals(0, control.activeConnections());
        assertEquals(0, metrics.snapshot().activeConnections());
        assertEquals(1, metrics.snapshot().acceptedConnections());
    }

    @Test
    void rejectedConnectionDoesNotConsumeAdmissionSlot() {
        var control = new ConnectionAdmissionControl(1, 1);
        var metrics = new ProxyMetrics();
        var accepted = new EmbeddedChannel(new ConnectionAdmissionHandler(control, metrics));
        var rejected = new EmbeddedChannel(new ConnectionAdmissionHandler(control, metrics));

        assertEquals(1, control.activeConnections());
        assertEquals(1, metrics.snapshot().activeConnections());
        assertEquals(1, metrics.snapshot().acceptedConnections());
        assertEquals(1, metrics.snapshot().rejectedConnections());
        assertEquals(1, metrics.snapshot().rejectedConnectionsByReason().get("global_limit"));

        rejected.finishAndReleaseAll();
        accepted.finishAndReleaseAll();
        assertEquals(0, control.activeConnections());
        assertEquals(0, metrics.snapshot().activeConnections());
    }
}

package dev.strataproxy.network;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConnectionAdmissionControlTest {
    @Test
    void enforcesGlobalLimit() {
        var control = new ConnectionAdmissionControl(1, 10);
        var first = control.acquire(new InetSocketAddress("127.0.0.1", 50000));
        var second = control.acquire(new InetSocketAddress("127.0.0.2", 50001));

        assertTrue(first.accepted());
        assertFalse(second.accepted());
        assertEquals("global_limit", second.rejectionReason());
        assertEquals(1, control.activeConnections());
    }

    @Test
    void enforcesPerAddressLimit() {
        var control = new ConnectionAdmissionControl(10, 1);
        var first = control.acquire(new InetSocketAddress("127.0.0.1", 50000));
        var second = control.acquire(new InetSocketAddress("127.0.0.1", 50001));

        assertTrue(first.accepted());
        assertFalse(second.accepted());
        assertEquals("per_address_limit", second.rejectionReason());
        assertEquals(1, control.activeConnectionsFor("127.0.0.1"));
    }

    @Test
    void releaseAllowsNewConnection() {
        var control = new ConnectionAdmissionControl(1, 1);
        var first = control.acquire(new InetSocketAddress("127.0.0.1", 50000));
        control.release(first);
        var second = control.acquire(new InetSocketAddress("127.0.0.1", 50001));

        assertTrue(second.accepted());
        assertEquals(1, control.activeConnections());
    }

    @Test
    void enforcesGlobalRateLimit() {
        var now = new AtomicLong(1_000);
        var control = new ConnectionAdmissionControl(10, 10, 1, 0, now::get);
        var first = control.acquire(new InetSocketAddress("127.0.0.1", 50000));
        var second = control.acquire(new InetSocketAddress("127.0.0.2", 50001));

        assertTrue(first.accepted());
        assertFalse(second.accepted());
        assertEquals("global_rate_limit", second.rejectionReason());
        assertEquals(1, control.activeConnections());

        now.addAndGet(1_000_000_000L);
        var third = control.acquire(new InetSocketAddress("127.0.0.2", 50002));

        assertTrue(third.accepted());
        assertEquals(2, control.activeConnections());
    }

    @Test
    void enforcesPerAddressRateLimitBeforeGlobalRateLimit() {
        var now = new AtomicLong(1_000);
        var control = new ConnectionAdmissionControl(10, 10, 1, 1, now::get);
        var first = control.acquire(new InetSocketAddress("127.0.0.1", 50000));
        var second = control.acquire(new InetSocketAddress("127.0.0.1", 50001));
        var third = control.acquire(new InetSocketAddress("127.0.0.2", 50002));

        assertTrue(first.accepted());
        assertFalse(second.accepted());
        assertEquals("per_address_rate_limit", second.rejectionReason());
        assertFalse(third.accepted());
        assertEquals("global_rate_limit", third.rejectionReason());
        assertEquals(1, control.activeConnections());
    }

    @Test
    void releaseDoesNotRefundRateWindow() {
        var now = new AtomicLong(1_000);
        var control = new ConnectionAdmissionControl(10, 10, 1, 0, now::get);
        var first = control.acquire(new InetSocketAddress("127.0.0.1", 50000));
        control.release(first);
        var second = control.acquire(new InetSocketAddress("127.0.0.2", 50001));

        assertFalse(second.accepted());
        assertEquals("global_rate_limit", second.rejectionReason());
        assertEquals(0, control.activeConnections());
    }

    @Test
    void incrementallyEvictsIdleRateBucketsWithoutScanningTheWholeMap() {
        var now = new AtomicLong(1_000);
        var control = new ConnectionAdmissionControl(1, 1, 0, 2_048, now::get);
        for (var index = 0; index < 1_024; index++) {
            var admission = control.acquire(new InetSocketAddress("192.0.2." + (index + 1), 50_000));
            assertTrue(admission.accepted());
            control.release(admission);
        }
        assertEquals(1_024, control.trackedRateAddresses());

        now.addAndGet(3_000_000_000L);
        for (var index = 0; index < 1_024; index++) {
            var admission = control.acquire(new InetSocketAddress("198.51.100.1", 50_000));
            assertTrue(admission.accepted());
            control.release(admission);
        }

        assertEquals(961, control.trackedRateAddresses());
    }
}

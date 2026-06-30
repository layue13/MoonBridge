package dev.strataproxy.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class NetworkTuningTest {
    @Test
    void acceptsValidWatermarks() {
        var tuning = new NetworkTuning(1024, 500, 128, 1024, 100, 10, 250);

        assertEquals(1024, tuning.maxFrameBytes());
        assertEquals(500, tuning.connectTimeoutMillis());
        assertEquals(128, tuning.writeBufferLowBytes());
        assertEquals(1024, tuning.writeBufferHighBytes());
        assertEquals(100, tuning.maxConnections());
        assertEquals(10, tuning.maxConnectionsPerAddress());
        assertEquals(250, tuning.initialHandshakeTimeoutMillis());
    }

    @Test
    void rejectsInvalidWatermarks() {
        assertThrows(IllegalArgumentException.class, () -> new NetworkTuning(1024, 500, 1024, 1024, 100, 10, 250));
        assertThrows(IllegalArgumentException.class, () -> new NetworkTuning(1024, 0, 128, 1024, 100, 10, 250));
        assertThrows(IllegalArgumentException.class, () -> new NetworkTuning(0, 500, 128, 1024, 100, 10, 250));
        assertThrows(IllegalArgumentException.class, () -> new NetworkTuning(1024, 500, 128, 1024, 0, 10, 250));
        assertThrows(IllegalArgumentException.class, () -> new NetworkTuning(1024, 500, 128, 1024, 100, 10, 0));
    }
}

package dev.moonbridge.testing;

import io.netty.buffer.ByteBufAllocator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LeakGateTest {
    @Test
    void reportsABufferThatIsNeverReleased() {
        LeakGate.drainLeaks();
        ByteBufAllocator.DEFAULT.directBuffer(16).writeInt(1); // deliberately dropped without release()
        assertTrue(LeakGate.drainLeaks().stream().anyMatch(report -> report.contains("LEAK")),
                "the gate must see an unreleased buffer, otherwise a clean run proves nothing");
    }

    @Test
    void staysQuietWhenEveryBufferIsReleased() {
        LeakGate.drainLeaks();
        for (int i = 0; i < 100; i++) ByteBufAllocator.DEFAULT.directBuffer(16).release();
        assertFalse(LeakGate.drainLeaks().stream().anyMatch(report -> report.contains("LEAK")));
    }
}

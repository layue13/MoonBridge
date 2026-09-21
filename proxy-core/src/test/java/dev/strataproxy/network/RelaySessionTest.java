package dev.strataproxy.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RelaySessionTest {
    @Test
    void forgeClientMarkerRequiresResetWhenSwitchingFromPreviousServer() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var session = new RelaySession("127.0.0.1:50000");
        session.legacyForgeClientDetected(new MinecraftHandshake(
                MinecraftProtocolProfile.PROTOCOL_1_7_10,
                "play.example.net\0FML\0",
                25565,
                2).legacyForgeClientMarker());

        var first = session.startForgeHandshakeTracker(4096, profile);
        assertFalse(first.consumeResetRequiredOnNextForgeServer());

        session.attach(null, null, "vanilla-1");
        var next = session.startForgeHandshakeTracker(4096, profile);
        try {
            assertTrue(next.consumeResetRequiredOnNextForgeServer());
            assertFalse(next.consumeResetRequiredOnNextForgeServer());
            assertTrue(session.legacyForgeClientDetected());
        } finally {
            next.close();
        }
    }

    @Test
    void completedLegacyForgeHandshakeDoesNotRequireSecondRegisterResetOnNextServer() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var session = new RelaySession("127.0.0.1:50000");
        session.legacyForgeClientDetected(true);
        var first = session.startForgeHandshakeTracker(4096, profile);
        try {
            MinecraftForgeHandshakeTrackerTest.advanceToComplete(first);
            session.attach(null, null, "forge-1");

            var next = session.startForgeHandshakeTracker(4096, profile);
            try {
                assertFalse(next.consumeResetRequiredOnNextForgeServer());
            } finally {
                next.close();
            }
        } finally {
            first.close();
        }
    }

    @Test
    void forgeTrackerSwapRollbackRestoresPreviousTrackerAfterFailedSwitch() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var session = new RelaySession("127.0.0.1:50000");
        session.attach(null, null, "forge-1");
        var previous = session.startForgeHandshakeTracker(4096, profile);

        var swap = session.beginForgeHandshakeTrackerSwap(4096, profile);
        assertSame(swap.next(), session.forgeHandshakeTracker());

        session.rollbackForgeHandshakeTrackerSwap(swap);

        assertSame(previous, session.forgeHandshakeTracker());
        previous.close();
    }

    @Test
    void forgeTrackerSwapCommitKeepsNextTrackerAfterSuccessfulSwitch() {
        var profile = MinecraftProtocolProfile.forVersion(MinecraftProtocolProfile.PROTOCOL_1_7_10);
        var session = new RelaySession("127.0.0.1:50000");
        session.attach(null, null, "forge-1");
        session.startForgeHandshakeTracker(4096, profile);

        var swap = session.beginForgeHandshakeTrackerSwap(4096, profile);
        session.commitForgeHandshakeTrackerSwap(swap);

        assertSame(swap.next(), session.forgeHandshakeTracker());
        swap.next().close();
    }
}

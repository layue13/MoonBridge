package dev.moonbridge.core.session.channel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionChannelsTest {
    @Test
    void writesHandshakeThenLoginStart() {
        var backend = new EmbeddedChannel();
        ByteBuf handshake = Unpooled.buffer().writeByte(7);
        assertTrue(SessionChannels.writeBackendLogin(backend, handshake, "Tester").isSuccess());
        ByteBuf first = backend.readOutbound();
        ByteBuf second = backend.readOutbound();
        assertEquals(7, first.getByte(0));
        assertEquals(0, second.getByte(0), "Login Start packet id");
        first.release();
        second.release();
        backend.finishAndReleaseAll();
    }

    @Test
    void unencodableLoginStartReleasesTheQueuedHandshake() {
        var backend = new EmbeddedChannel();
        ByteBuf handshake = Unpooled.buffer().writeByte(7);
        // Longer than the 1.7.10 login name limit, so Login Start cannot be encoded.
        var write = SessionChannels.writeBackendLogin(backend, handshake, "x".repeat(64));
        assertFalse(write.isSuccess());
        assertFalse(backend.isOpen());
        assertEquals(0, handshake.refCnt());
    }
}

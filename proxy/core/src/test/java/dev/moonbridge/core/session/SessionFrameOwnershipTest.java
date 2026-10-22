package dev.moonbridge.core.session;

import dev.moonbridge.core.backend.InMemoryBackendCatalog;
import io.netty.buffer.DefaultByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What Session does with input it cannot treat as a frame of its own session. */
final class SessionFrameOwnershipTest {
    private static ProxySessionListener owner() {
        return new ProxySessionListener(new InetSocketAddress("127.0.0.1", 0), new InMemoryBackendCatalog());
    }

    @Test
    void aNonBufferMessageFromTheClientIsReleasedAndClosesTheSession() throws Exception {
        var owner = owner();
        var frontend = new EmbeddedChannel();
        try {
            var session = new Session(owner, frontend);
            frontend.pipeline().addLast(session);

            var holder = new DefaultByteBufHolder(Unpooled.buffer().writeByte(1));
            frontend.writeInbound(holder);

            assertEquals(0, holder.refCnt());
            assertFalse(frontend.isOpen(), "malformed input ends the session");
        } finally {
            frontend.finishAndReleaseAll();
            owner.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void messagesArrivingOnAChannelOutsideTheSessionAreReleasedWithoutEndingIt() throws Exception {
        var owner = owner();
        var frontend = new EmbeddedChannel();
        var stranger = new EmbeddedChannel(); // e.g. a failed backend dial that still holds the shared handler
        try {
            var session = new Session(owner, frontend);
            frontend.pipeline().addLast(session);
            stranger.pipeline().addLast(session);

            var bytes = Unpooled.buffer().writeByte(1);
            var holder = new DefaultByteBufHolder(Unpooled.buffer().writeByte(1));
            stranger.writeInbound(bytes);
            stranger.writeInbound(holder);

            assertEquals(0, bytes.refCnt());
            assertEquals(0, holder.refCnt());
            assertTrue(frontend.isOpen(), "traffic from an unrelated channel must not close the real session");
        } finally {
            stranger.finishAndReleaseAll();
            frontend.finishAndReleaseAll();
            owner.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }
}

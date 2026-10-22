package dev.moonbridge.core.session;

import dev.moonbridge.api.TransferStatus;
import dev.moonbridge.core.backend.InMemoryBackendCatalog;
import io.netty.channel.DefaultEventLoop;
import io.netty.channel.local.LocalChannel;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SessionTransferDispatchTest {
    @Test
    void transferAfterEventLoopShutdownReturnsDisconnectedResult() throws Exception {
        var eventLoop = new DefaultEventLoop();
        var frontend = new LocalChannel();
        try {
            eventLoop.register(frontend).syncUninterruptibly();
            var session = new Session(null, frontend);
            eventLoop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();

            var result = session.transferTo("lobby").toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertEquals(TransferStatus.PLAYER_NOT_CONNECTED, result.status());
        } finally {
            frontend.close();
            eventLoop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
        }
    }

    @Test
    void closeAfterEventLoopShutdownRemovesTheSession() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        var owner = new ProxySessionListener(new InetSocketAddress("127.0.0.1", 0), catalog);
        var eventLoop = new DefaultEventLoop();
        var frontend = new LocalChannel();
        try {
            eventLoop.register(frontend).syncUninterruptibly();
            var session = new Session(owner, frontend);
            owner.allSessions().add(session);
            eventLoop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();

            session.closePair();
            assertTrue(owner.allSessions().isEmpty(), "a terminated event loop must not strand the session");
        } finally {
            frontend.close();
            eventLoop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
            owner.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }
}

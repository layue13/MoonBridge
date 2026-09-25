package dev.strataproxy.core.session;

import dev.strataproxy.api.TransferStatus;
import dev.strataproxy.core.backend.BackendId;
import dev.strataproxy.core.backend.BackendOwner;
import dev.strataproxy.core.backend.BackendRegistration;
import dev.strataproxy.core.backend.InMemoryBackendCatalog;
import io.netty.channel.DefaultEventLoop;
import io.netty.channel.local.LocalChannel;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
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
    void closeAfterEventLoopShutdownReleasesTheSessionAndItsCapacity() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        var registered = catalog.register(new BackendRegistration(new BackendId("lobby"),
                new BackendOwner("test", 1), URI.create("tcp://127.0.0.1:25565"), 1));
        var claim = catalog.reserve(registered.handle(), 1).orElseThrow();
        assertTrue(claim.commit());
        var owner = new ProxySessionListener(new InetSocketAddress("127.0.0.1", 0), catalog);
        var eventLoop = new DefaultEventLoop();
        var frontend = new LocalChannel();
        try {
            eventLoop.register(frontend).syncUninterruptibly();
            var session = new Session(owner, frontend);
            var reservation = Session.class.getDeclaredField("reservation");
            reservation.setAccessible(true);
            reservation.set(session, claim);
            owner.allSessions().add(session);
            eventLoop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();

            session.closePair();
            assertTrue(claim.isClosed(), "a terminated event loop must not strand backend capacity");
            assertTrue(owner.allSessions().isEmpty(), "a terminated event loop must not strand the session");
            assertEquals(0, catalog.find(registered.handle().id()).orElseThrow().connectedPlayers());
        } finally {
            frontend.close();
            eventLoop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
            owner.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }
}

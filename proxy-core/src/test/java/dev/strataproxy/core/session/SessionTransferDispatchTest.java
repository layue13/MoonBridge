package dev.strataproxy.core.session;

import dev.strataproxy.api.TransferStatus;
import io.netty.channel.DefaultEventLoop;
import io.netty.channel.local.LocalChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}

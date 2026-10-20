package dev.moonbridge.clientws.net;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WebSocketConnectContextTest {

    @AfterEach
    void clear() {
        WebSocketConnectContext.clear();
    }

    @Test
    void takeMatchesHostNameAndPortOnce() throws Exception {
        WebSocketAddress a = WebSocketAddress.parse("wss://cdn.example.com/a");
        WebSocketConnectContext.set(a);
        InetAddress resolved = InetAddress.getByAddress("cdn.example.com", new byte[] { 1, 2, 3, 4 });
        assertSame(a, WebSocketConnectContext.take(resolved, 443));
        assertNull(WebSocketConnectContext.take(resolved, 443));
    }

    @Test
    void otherHostSameIpIsNotMatched() throws Exception {
        WebSocketConnectContext.set(WebSocketAddress.parse("wss://a.example.com/"));
        InetAddress other = InetAddress.getByAddress("b.example.com", new byte[] { 1, 2, 3, 4 });
        assertNull(WebSocketConnectContext.take(other, 443));
    }

    @Test
    void portMismatchAndPlainParseClear() throws Exception {
        InetAddress h = InetAddress.getByAddress("h", new byte[] { 1, 2, 3, 4 });
        WebSocketConnectContext.set(WebSocketAddress.parse("ws://h:8080/"));
        assertNull(WebSocketConnectContext.take(h, 25565));

        WebSocketConnectContext.set(WebSocketAddress.parse("ws://h:8080/"));
        WebSocketConnectContext.set(null); // a later vanilla parse
        assertNull(WebSocketConnectContext.take(h, 8080));
    }

    @Test
    void ipLiteralsCompareAddresses() throws Exception {
        WebSocketAddress v4 = WebSocketAddress.parse("ws://127.0.0.1:8080/mc");
        WebSocketConnectContext.set(v4);
        assertSame(v4, WebSocketConnectContext.take(InetAddress.getByName("127.0.0.1"), 8080));

        WebSocketAddress v6 = WebSocketAddress.parse("ws://[::1]:8080/mc");
        WebSocketConnectContext.set(v6);
        assertSame(v6, WebSocketConnectContext.take(InetAddress.getByName("::1"), 8080));
    }

    @Test
    void connectorThreadInheritsFromCreatingThread() throws Exception {
        WebSocketAddress a = WebSocketAddress.parse("wss://mc.example.com/mc");
        WebSocketConnectContext.set(a);
        final AtomicReference<WebSocketAddress> seen = new AtomicReference<WebSocketAddress>();
        Thread t = new Thread(() -> {
            try {
                seen.set(
                    WebSocketConnectContext
                        .take(InetAddress.getByAddress("mc.example.com", new byte[] { 9, 9, 9, 9 }), 443));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        WebSocketConnectContext.clear(); // what GuiConnecting does once the thread exists
        t.start();
        t.join();
        // cleared on the creating thread after construction: the connector thread still has its inherited copy
        assertSame(a, seen.get());
        assertNull(
            WebSocketConnectContext.take(InetAddress.getByAddress("mc.example.com", new byte[] { 9, 9, 9, 9 }), 443));
    }
}

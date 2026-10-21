package dev.moonbridge.clientws.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WebSocketAddressTest {

    @Test
    void wssDefaults() {
        WebSocketAddress a = WebSocketAddress.parse("wss://mc.example.com/mc");
        assertTrue(a.isSecure());
        assertEquals("mc.example.com", a.host());
        assertEquals(443, a.port());
        assertEquals("/mc", a.pathAndQuery());
        assertEquals("mc.example.com", a.hostHeader());
        assertFalse(a.isIpLiteral());
    }

    @Test
    void wsDefaultsAndEmptyPath() {
        WebSocketAddress a = WebSocketAddress.parse("  WS://Example.org  ");
        assertFalse(a.isSecure());
        assertEquals("Example.org", a.host());
        assertEquals(80, a.port());
        assertEquals("/", a.pathAndQuery());
        assertEquals("Example.org", a.hostHeader());
    }

    @Test
    void explicitPortsAndHostHeader() {
        WebSocketAddress a = WebSocketAddress.parse("ws://1.2.3.4:8080/mc");
        assertEquals("1.2.3.4", a.host());
        assertEquals(8080, a.port());
        assertTrue(a.isIpLiteral());
        assertEquals("1.2.3.4:8080", a.hostHeader());

        assertEquals(
            "h",
            WebSocketAddress.parse("ws://h:80/")
                .hostHeader());
        assertEquals(
            "h:443",
            WebSocketAddress.parse("ws://h:443/")
                .hostHeader());
        assertEquals(
            "h:80",
            WebSocketAddress.parse("wss://h:80")
                .hostHeader());
        assertEquals(
            "h",
            WebSocketAddress.parse("wss://h:443")
                .hostHeader());
    }

    @Test
    void ipv6Brackets() {
        WebSocketAddress a = WebSocketAddress.parse("wss://[2001:db8::1]:8443/x");
        assertEquals("2001:db8::1", a.host());
        assertEquals(8443, a.port());
        assertTrue(a.isIpLiteral());
        assertEquals("[2001:db8::1]:8443", a.hostHeader());

        WebSocketAddress b = WebSocketAddress.parse("ws://[::1]");
        assertEquals("::1", b.host());
        assertEquals(80, b.port());
        assertEquals("[::1]", b.hostHeader());
        assertEquals("/", b.pathAndQuery());
    }

    @Test
    void pathAndQueryKeptFragmentDropped() {
        assertEquals(
            "/a/b?token=x&y=1",
            WebSocketAddress.parse("wss://h/a/b?token=x&y=1#frag")
                .pathAndQuery());
        assertEquals(
            "/?q=1",
            WebSocketAddress.parse("wss://h?q=1")
                .pathAndQuery());
        assertEquals(
            "/?q=1",
            WebSocketAddress.parse("wss://h:9000?q=1")
                .pathAndQuery());
        assertEquals(
            9000,
            WebSocketAddress.parse("wss://h:9000?q=1")
                .port());
    }

    @Test
    void detection() {
        assertTrue(WebSocketAddress.looksLikeWebSocket("wss://x"));
        assertTrue(WebSocketAddress.looksLikeWebSocket(" Ws://x"));
        assertFalse(WebSocketAddress.looksLikeWebSocket("mc.example.com:25565"));
        assertFalse(WebSocketAddress.looksLikeWebSocket("wsx.example.com"));
        assertFalse(WebSocketAddress.looksLikeWebSocket("http://x"));
        assertFalse(WebSocketAddress.looksLikeWebSocket(null));
    }

    @Test
    void rejectsGarbage() {
        String[] bad = { "ws://", "wss:///path", "ws://:80", "ws://host:", "ws://host:0", "ws://host:65536",
            "ws://host:12ab", "ws://host:123456", "ws://user@host", "ws://[::1", "ws://[zz::1]", "ws://[::1]x",
            "ws://ho st", "ws://host/a b", "ws://host/é", "ws://exa$mple.com", "ws://.host", "ws://a..b", "http://host",
            "host:25565" };
        for (String s : bad) {
            assertThrows(IllegalArgumentException.class, () -> WebSocketAddress.parse(s), s);
        }
    }
}

package dev.moonbridge.core.websocket;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ClientAddressPolicyTest {
    private static final InetSocketAddress EDGE = new InetSocketAddress("173.245.48.10", 41000);
    private static final InetSocketAddress STRANGER = new InetSocketAddress("203.0.113.50", 41000);

    private static HttpHeaders headers(String name, String... values) {
        var headers = new DefaultHttpHeaders();
        for (String value : values) headers.add(name, value);
        return headers;
    }

    private static String host(InetSocketAddress address) { return address.getAddress().getHostAddress(); }

    @Test
    void untrustedPeerKeepsItsOwnAddressWhateverItClaims() {
        var policy = new ClientAddressPolicy(List.of("173.245.48.0/20"), "CF-Connecting-IP");
        assertSame(STRANGER, policy.resolve(STRANGER, headers("CF-Connecting-IP", "198.51.100.1")));
        assertSame(EDGE, ClientAddressPolicy.direct().resolve(EDGE, headers("X-Forwarded-For", "198.51.100.1")));
    }

    @Test
    void trustedPeerSuppliesTheHeaderAddressAndKeepsThePeerPort() {
        var policy = new ClientAddressPolicy(List.of("173.245.48.0/20"), "cf-connecting-ip");
        var resolved = policy.resolve(EDGE, headers("CF-Connecting-IP", "2001:db8::7"));
        assertEquals("2001:db8:0:0:0:0:0:7", host(resolved));
        assertEquals(EDGE.getPort(), resolved.getPort());
        assertSame(EDGE, policy.resolve(EDGE, new DefaultHttpHeaders()));
    }

    @Test
    void forwardedForIsReadFromTheRightSkippingTrustedHops() {
        var policy = new ClientAddressPolicy(List.of("173.245.48.0/20", "10.0.0.0/8"), "X-Forwarded-For");
        // The leftmost entry is client-controlled; the rightmost untrusted hop is what the edge saw.
        assertEquals("198.51.100.9", host(policy.resolve(EDGE,
                headers("X-Forwarded-For", "6.6.6.6, 198.51.100.9", "10.1.2.3"))));
        // When every hop is trusted, the leftmost one is the best remaining answer.
        assertEquals("10.9.9.9", host(policy.resolve(EDGE, headers("X-Forwarded-For", "10.9.9.9, 10.1.2.3"))));
        assertEquals("198.51.100.9", host(policy.resolve(EDGE, headers("X-Forwarded-For", "198.51.100.9:5555"))));
        assertEquals("2001:db8:0:0:0:0:0:1", host(policy.resolve(EDGE, headers("X-Forwarded-For", "[2001:db8::1]:5555"))));
    }

    @Test
    void ipv4MappedPeersMatchIpv4Ranges() throws Exception {
        var policy = new ClientAddressPolicy(List.of("127.0.0.1"), "X-Real-IP");
        var mapped = new InetSocketAddress(java.net.InetAddress.getByAddress(
                new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, 127, 0, 0, 1}), 1);
        assertEquals("198.51.100.2", host(policy.resolve(mapped, headers("X-Real-IP", "198.51.100.2"))));
    }

    @Test
    void malformedValuesFromATrustedPeerAreRejected() {
        var policy = new ClientAddressPolicy(List.of("173.245.48.0/20"), "CF-Connecting-IP");
        assertThrows(IllegalArgumentException.class, () -> policy.resolve(EDGE, headers("CF-Connecting-IP", "example.com")));
        assertThrows(IllegalArgumentException.class, () -> policy.resolve(EDGE, headers("CF-Connecting-IP", "1.1.1.1", "2.2.2.2")));
        var forwarded = new ClientAddressPolicy(List.of("173.245.48.0/20"), "X-Forwarded-For");
        assertThrows(IllegalArgumentException.class, () -> forwarded.resolve(EDGE, headers("X-Forwarded-For", "1.1.1.1,,")));
    }

    @Test
    void invalidSettingsFailAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new ClientAddressPolicy(List.of("10.0.0.0/33"), "X-Forwarded-For"));
        assertThrows(IllegalArgumentException.class, () -> new ClientAddressPolicy(List.of("localhost"), "X-Forwarded-For"));
        assertThrows(IllegalArgumentException.class, () -> new ClientAddressPolicy(List.of(), "Bad Header"));
    }
}

package dev.moonbridge.core.websocket;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.util.NetUtil;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Decides which address identifies a WebSocket player. A forwarded-address header is honored only when the
 * TCP peer is a configured trusted proxy (a CDN edge or a local reverse proxy); otherwise any client could
 * claim another player's IP and bypass address bans.
 */
public final class ClientAddressPolicy {
    private static final String FORWARDED_FOR = "x-forwarded-for";
    private final List<Cidr> trustedProxies;
    private final String header;

    /** Header names are matched case-insensitively; X-Forwarded-For is read from the right. */
    public ClientAddressPolicy(List<String> trustedProxies, String header) {
        this.trustedProxies = trustedProxies.stream().map(Cidr::parse).toList();
        this.header = Objects.requireNonNull(header, "header").toLowerCase(Locale.ROOT);
        if (!this.header.matches("[a-z0-9!#$%&'*+.^_`|~-]+")) throw new IllegalArgumentException("invalid header name: " + header);
    }

    /** Uses only the TCP peer address. */
    public static ClientAddressPolicy direct() { return new ClientAddressPolicy(List.of(), FORWARDED_FOR); }

    /**
     * Returns the player address for this upgrade request. A trusted peer that sends no header keeps its own
     * address; a trusted peer that sends a malformed one is rejected, so a misconfigured proxy fails visibly.
     */
    public InetSocketAddress resolve(InetSocketAddress peer, HttpHeaders headers) {
        if (!trusted(peer.getAddress())) return peer;
        List<String> values = headers.getAll(header);
        if (values.isEmpty()) return peer;
        InetAddress client;
        if (header.equals(FORWARDED_FOR)) {
            client = rightmostUntrusted(String.join(",", values));
        } else {
            if (values.size() != 1) throw new IllegalArgumentException("repeated " + header + " header");
            client = literal(values.get(0));
        }
        return new InetSocketAddress(client, peer.getPort());
    }

    private InetAddress rightmostUntrusted(String chain) {
        String[] hops = chain.split(",", -1);
        InetAddress candidate = null;
        for (int i = hops.length - 1; i >= 0; i--) {
            candidate = literal(hops[i]);
            if (!trusted(candidate)) return candidate;
        }
        return candidate;
    }

    private boolean trusted(InetAddress address) {
        for (Cidr block : trustedProxies) if (block.contains(address)) return true;
        return false;
    }

    /** Parses one IP literal without DNS; accepts "[v6]", "[v6]:port" and "v4:port" forms. */
    static InetAddress literal(String text) {
        String value = text.trim();
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            if (end < 0) throw new IllegalArgumentException("invalid forwarded address");
            value = value.substring(1, end);
        } else if (value.indexOf(':') > 0 && value.indexOf(':') == value.lastIndexOf(':')) {
            value = value.substring(0, value.indexOf(':'));
        }
        byte[] bytes = NetUtil.createByteArrayFromIpAddressString(value);
        if (bytes == null) throw new IllegalArgumentException("invalid forwarded address");
        try {
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private record Cidr(byte[] network, int prefix) {
        static Cidr parse(String text) {
            String value = Objects.requireNonNull(text, "trusted proxy").trim();
            int slash = value.indexOf('/');
            byte[] network = NetUtil.createByteArrayFromIpAddressString(slash < 0 ? value : value.substring(0, slash));
            if (network == null) throw new IllegalArgumentException("invalid trusted proxy: " + text);
            int prefix;
            try {
                prefix = slash < 0 ? network.length * 8 : Integer.parseInt(value.substring(slash + 1));
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("invalid trusted proxy: " + text, invalid);
            }
            if (prefix < 0 || prefix > network.length * 8) throw new IllegalArgumentException("invalid trusted proxy: " + text);
            byte[] unmapped = unmapped(network);
            if (unmapped != network) {
                // contains() compares mapped peers as IPv4, so an IPv4-mapped block has to be stored as IPv4 too.
                if (prefix < 96) throw new IllegalArgumentException("invalid trusted proxy: " + text);
                return new Cidr(unmapped, prefix - 96);
            }
            return new Cidr(network, prefix);
        }

        boolean contains(InetAddress address) {
            byte[] bytes = unmapped(address.getAddress());
            if (bytes.length != network.length) return false;
            int full = prefix / 8;
            for (int i = 0; i < full; i++) if (bytes[i] != network[i]) return false;
            int rest = prefix % 8;
            if (rest == 0) return true;
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (bytes[full] & mask) == (network[full] & mask);
        }

        /** Dual-stack sockets report IPv4 peers as ::ffff:a.b.c.d; compare those as IPv4. */
        private static byte[] unmapped(byte[] bytes) {
            if (bytes.length != 16) return bytes;
            for (int i = 0; i < 10; i++) if (bytes[i] != 0) return bytes;
            if (bytes[10] != (byte) 0xFF || bytes[11] != (byte) 0xFF) return bytes;
            return new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]};
        }
    }
}

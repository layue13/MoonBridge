package dev.moonbridge.clientws.net;

import java.util.Locale;

/**
 * A parsed {@code ws://} or {@code wss://} server address.
 *
 * <p>
 * Only what the client needs is accepted: scheme, host (DNS name, IPv4 or bracketed IPv6), optional port and an
 * optional path/query. User info is rejected, a fragment is dropped (it is never sent), and the path must be printable
 * ASCII without spaces so it can go into the HTTP request line verbatim.
 */
public final class WebSocketAddress {

    private final boolean secure;
    private final String host;
    private final int port;
    private final String pathAndQuery;

    private WebSocketAddress(boolean secure, String host, int port, String pathAndQuery) {
        this.secure = secure;
        this.host = host;
        this.port = port;
        this.pathAndQuery = pathAndQuery;
    }

    /**
     * True if {@code address} starts with {@code ws://} or {@code wss://} (case-insensitive, leading space ignored).
     */
    public static boolean looksLikeWebSocket(String address) {
        if (address == null) {
            return false;
        }
        String s = address.trim()
            .toLowerCase(Locale.ROOT);
        return s.startsWith("ws://") || s.startsWith("wss://");
    }

    /**
     * Parses a ws/wss URL.
     *
     * @throws IllegalArgumentException with a human-readable reason if the address is not a valid ws/wss URL
     */
    public static WebSocketAddress parse(String address) {
        if (address == null) {
            throw new IllegalArgumentException("address is null");
        }
        String s = address.trim();
        String lower = s.toLowerCase(Locale.ROOT);
        boolean secure;
        int rest;
        if (lower.startsWith("wss://")) {
            secure = true;
            rest = 6;
        } else if (lower.startsWith("ws://")) {
            secure = false;
            rest = 5;
        } else {
            throw new IllegalArgumentException("not a ws:// or wss:// address");
        }

        int hash = s.indexOf('#', rest);
        if (hash >= 0) {
            s = s.substring(0, hash);
        }
        int authorityEnd = s.length();
        for (int i = rest; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '/' || c == '?') {
                authorityEnd = i;
                break;
            }
        }
        String authority = s.substring(rest, authorityEnd);
        String path = s.substring(authorityEnd);
        if (authority.isEmpty()) {
            throw new IllegalArgumentException("missing host");
        }
        if (authority.indexOf('@') >= 0) {
            throw new IllegalArgumentException("user info is not supported");
        }

        String host;
        String portText = null;
        if (authority.charAt(0) == '[') {
            int close = authority.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("unterminated IPv6 literal");
            }
            host = authority.substring(1, close);
            if (!isIpv6Literal(host)) {
                throw new IllegalArgumentException("invalid IPv6 literal: " + host);
            }
            String after = authority.substring(close + 1);
            if (!after.isEmpty()) {
                if (after.charAt(0) != ':') {
                    throw new IllegalArgumentException("unexpected characters after IPv6 literal");
                }
                portText = after.substring(1);
            }
        } else {
            int colon = authority.indexOf(':');
            if (colon >= 0) {
                host = authority.substring(0, colon);
                portText = authority.substring(colon + 1);
            } else {
                host = authority;
            }
            if (!isHostName(host)) {
                throw new IllegalArgumentException("invalid host: " + host);
            }
        }

        int port = secure ? 443 : 80;
        if (portText != null) {
            port = parsePort(portText);
        }

        if (path.isEmpty()) {
            path = "/";
        } else if (path.charAt(0) == '?') {
            path = "/" + path;
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c <= 0x20 || c >= 0x7f) {
                throw new IllegalArgumentException("path must be printable ASCII without spaces (percent-encode it)");
            }
        }
        return new WebSocketAddress(secure, host, port, path);
    }

    private static int parsePort(String text) {
        if (text.isEmpty() || text.length() > 5) {
            throw new IllegalArgumentException("invalid port: '" + text + "'");
        }
        int port = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("invalid port: '" + text + "'");
            }
            port = port * 10 + (c - '0');
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        return port;
    }

    private static boolean isHostName(String host) {
        if (host.isEmpty() || host.length() > 253) {
            return false;
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '-'
                || c == '.'
                || c == '_';
            if (!ok) {
                return false;
            }
        }
        return host.charAt(0) != '.' && host.charAt(0) != '-' && !host.contains("..");
    }

    private static boolean isIpv6Literal(String host) {
        if (host.length() < 2 || host.indexOf(':') < 0) {
            return false;
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                || (c >= 'A' && c <= 'F')
                || c == ':'
                || c == '.';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    public boolean isSecure() {
        return secure;
    }

    /** Host as used for DNS, SNI and certificate checks; IPv6 literals without brackets. */
    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public boolean isIpv6Literal() {
        return host.indexOf(':') >= 0;
    }

    /** True for an IPv4 or IPv6 literal (no SNI is sent for those). */
    public boolean isIpLiteral() {
        if (isIpv6Literal()) {
            return true;
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if ((c < '0' || c > '9') && c != '.') {
                return false;
            }
        }
        return true;
    }

    /** Request target for the HTTP request line: path (at least {@code /}) plus {@code ?query} if present. */
    public String pathAndQuery() {
        return pathAndQuery;
    }

    /** Value of the HTTP Host header: host (bracketed if IPv6) plus {@code :port} only if the port is not default. */
    public String hostHeader() {
        String h = isIpv6Literal() ? "[" + host + "]" : host;
        int defaultPort = secure ? 443 : 80;
        return port == defaultPort ? h : h + ":" + port;
    }

    @Override
    public String toString() {
        return (secure ? "wss://" : "ws://") + hostHeader() + pathAndQuery;
    }
}

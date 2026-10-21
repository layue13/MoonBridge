package dev.moonbridge.clientws.net;

import java.net.InetAddress;
import java.util.Arrays;

/**
 * Carries a parsed ws/wss URL from {@code ServerAddress.func_78860_a} to {@code NetworkManager.provideLanClient}.
 *
 * <p>
 * Both callers of the parser parse and connect with the same logical flow: {@code OldServerPinger} parses and connects
 * on one pinger thread, and {@code GuiConnecting} parses on the client thread and then connects on a "Server Connector"
 * thread that it creates right after parsing. The value is therefore an {@link InheritableThreadLocal}: the connector
 * thread inherits it from the client thread at construction. It is keyed by thread and checked against host name and
 * port, never looked up by resolved IP, because behind a CDN many hostnames share one IP. Every parse overwrites it,
 * {@link #take} removes it, and {@code GuiConnecting} clears the client-thread copy once the connector thread exists.
 */
public final class WebSocketConnectContext {

    private static final InheritableThreadLocal<WebSocketAddress> PENDING = new InheritableThreadLocal<WebSocketAddress>();

    private WebSocketConnectContext() {}

    /** Records the result of the latest address parse on this thread ({@code null} for a plain address). */
    public static void set(WebSocketAddress address) {
        if (address == null) {
            PENDING.remove();
        } else {
            PENDING.set(address);
        }
    }

    public static void clear() {
        PENDING.remove();
    }

    /**
     * Removes and returns the pending ws URL if it belongs to a connection to {@code target:port}, else null. The host
     * check uses the name the {@link InetAddress} was created from (no reverse lookup); for IP-literal URLs it compares
     * the address bytes.
     */
    public static WebSocketAddress take(InetAddress target, int port) {
        WebSocketAddress pending = PENDING.get();
        PENDING.remove();
        if (pending == null || target == null || pending.port() != port) {
            return null;
        }
        return matchesHost(pending, target) ? pending : null;
    }

    static boolean matchesHost(WebSocketAddress address, InetAddress target) {
        if (address.isIpLiteral()) {
            try {
                // A literal never triggers a DNS lookup.
                return Arrays.equals(
                    InetAddress.getByName(address.host())
                        .getAddress(),
                    target.getAddress());
            } catch (java.net.UnknownHostException e) {
                return false;
            }
        }
        String text = target.toString(); // "hostname/ip"; hostname part is empty if created from a literal
        int slash = text.indexOf('/');
        String name = slash >= 0 ? text.substring(0, slash) : text;
        return name.equalsIgnoreCase(address.host());
    }
}

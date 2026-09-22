package dev.strataproxy.infrastructure.minecraft;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

final class ClientAddress {
    static final AttributeKey<InetSocketAddress> PROXY_PROTOCOL_ADDRESS =
            AttributeKey.valueOf("strataproxy.proxyProtocolAddress");

    private ClientAddress() {
    }

    static SocketAddress socketAddress(Channel channel) {
        var proxied = channel.attr(PROXY_PROTOCOL_ADDRESS).get();
        return proxied == null ? channel.remoteAddress() : proxied;
    }

    static String text(Channel channel) {
        return text(socketAddress(channel));
    }

    static String text(SocketAddress address) {
        if (address instanceof InetSocketAddress inet) {
            var host = inet.getAddress() == null ? inet.getHostString() : inet.getAddress().getHostAddress();
            if (host == null || host.isBlank()) {
                host = "unknown";
            }
            return (host.contains(":") ? "[" + host + "]" : host) + ":" + inet.getPort();
        }
        return address == null ? "" : address.toString();
    }
}

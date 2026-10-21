package dev.moonbridge.clientws.bridge;

import dev.moonbridge.clientws.net.WebSocketAddress;

/** Implemented on {@code NetworkManager} by mixin: the ws/wss URL this client connection must use, if any. */
public interface WebSocketTarget {

    WebSocketAddress moonbridgews$getWebSocket();

    void moonbridgews$setWebSocket(WebSocketAddress address);
}

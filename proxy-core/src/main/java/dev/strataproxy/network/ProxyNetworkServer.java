package dev.strataproxy.network;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletionStage;

/**
 * Lifecycle abstraction for the proxy frontend network server.
 */
public interface ProxyNetworkServer extends AutoCloseable {
    /**
     * Binds the frontend server.
     *
     * @param address address to bind
     * @return stage completed when bind succeeds or fails
     */
    CompletionStage<Void> bind(InetSocketAddress address);

    /**
     * Returns the address selected by the server bind operation.
     *
     * @return actual bound address, or {@code null} before bind completes
     */
    InetSocketAddress bindAddress();

    /**
     * Stops the server and releases network resources.
     */
    @Override
    void close();
}

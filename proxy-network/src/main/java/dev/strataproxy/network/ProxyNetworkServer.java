package dev.strataproxy.network;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletionStage;

public interface ProxyNetworkServer extends AutoCloseable {
    CompletionStage<Void> bind(InetSocketAddress address);

    InetSocketAddress bindAddress();

    @Override
    void close();
}

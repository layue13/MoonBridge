package dev.strataproxy.api;

/** A registered backend channel handler. Closing the subscription more than once is harmless. */
public interface BackendChannelSubscription extends AutoCloseable {
    @Override
    void close();
}

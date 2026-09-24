package dev.strataproxy.backend.api;

/** Receives one message from the proxy/backend channel broker. */
@FunctionalInterface
public interface BackendMessageListener {
    void onMessage(BackendMessage message);
}

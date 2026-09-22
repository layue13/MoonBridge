package dev.strataproxy.backend.api;

/** Receives one proxy-to-backend plugin message. */
@FunctionalInterface
public interface BackendMessageListener {
    void onMessage(BackendMessage message);
}

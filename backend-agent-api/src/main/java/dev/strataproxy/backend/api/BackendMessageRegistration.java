package dev.strataproxy.backend.api;

/** Handle for one backend-agent message listener. */
public interface BackendMessageRegistration extends AutoCloseable {
    @Override
    void close();
}

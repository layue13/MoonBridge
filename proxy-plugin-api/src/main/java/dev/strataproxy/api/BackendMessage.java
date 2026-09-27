package dev.strataproxy.api;

import java.util.Objects;

/** An inbound request from a backend, with identity taken from its authenticated connection. */
public record BackendMessage(
        String backendName,
        String instanceId,
        long epoch,
        String channel,
        byte[] payload) {

    public BackendMessage {
        backendName = requireText(backendName, "backendName");
        instanceId = requireText(instanceId, "instanceId");
        if (epoch <= 0) {
            throw new IllegalArgumentException("epoch must be positive");
        }
        channel = requireText(channel, "channel");
        payload = Objects.requireNonNull(payload, "payload").clone();
    }

    /** Returns a copy so callers cannot mutate the message held by the proxy. */
    @Override
    public byte[] payload() {
        return payload.clone();
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
